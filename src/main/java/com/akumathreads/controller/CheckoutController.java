package com.akumathreads.controller;

import com.akumathreads.dto.CheckoutForm;
import com.akumathreads.dto.OrderItemRequest;
import com.akumathreads.exception.InsufficientStockException;
import com.akumathreads.model.DiscountCode;
import com.akumathreads.model.Order;
import com.akumathreads.model.SessionCart;
import com.akumathreads.model.User;
import com.akumathreads.pricing.CartPricing;
import com.akumathreads.service.DiscountCodeService;
import com.akumathreads.service.OrderService;
import com.akumathreads.service.StripeService;
import com.akumathreads.service.UserService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checkout flow controller.
 *
 * <h2>Flow (P0-2 / P0-3 fix — order created at PI time)</h2>
 * <ol>
 *   <li>GET /checkout — render the checkout form.</li>
 *   <li>POST /checkout/payment-intent (AJAX) — compute pricing via {@link CartPricing},
 *       create the Stripe PaymentIntent, then immediately create a PENDING order and
 *       reserve stock. Stores {@code pendingOrderId} and {@code pendingPaymentIntentId}
 *       in the session.</li>
 *   <li>Browser calls {@code stripe.confirmCardPayment} — card never touches our server.</li>
 *   <li>POST /checkout (form submit) — verifies the PI is succeeded, transitions the
 *       PENDING order to PAID, clears the cart, redirects to order confirmation.</li>
 *   <li>Stripe webhook (async) — transitions PAID → PROCESSING, sends confirmation
 *       email, pushes to Printful, redeems the discount code.</li>
 * </ol>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

    private final OrderService        orderService;
    private final UserService         userService;
    private final DiscountCodeService discountCodeService;
    private final StripeService       stripeService;

    // ── GET /checkout ──────────────────────────────────────────────────────────

    @GetMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public String checkoutPage(@AuthenticationPrincipal UserDetails principal,
                               HttpSession session, Model model) {
        SessionCart cart = resolveCart(session);
        if (cart.isEmpty()) return "redirect:/shop";

        CheckoutForm form = new CheckoutForm();
        form.setEmail(principal.getUsername());

        model.addAttribute("cart",                 cart);
        model.addAttribute("form",                 form);
        model.addAttribute("stripePublishableKey", stripeService.getPublishableKey());
        return "checkout";
    }

    // ── POST /checkout/payment-intent (AJAX) ──────────────────────────────────

    /**
     * Creates a Stripe PaymentIntent and a PENDING order (with stock reserved) in one
     * atomic step. Returns pricing breakdown for the JS to update the displayed totals.
     *
     * <p>All shipping fields are accepted here so the Order can be fully populated
     * before the customer enters card details. If PI creation or order creation fails,
     * a JSON error is returned and no charge is initiated.
     */
    @PostMapping("/checkout/payment-intent")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createPaymentIntent(
            @RequestParam String email,
            @RequestParam(required = false, defaultValue = "") String fullName,
            @RequestParam(required = false, defaultValue = "") String address,
            @RequestParam(required = false, defaultValue = "") String address2,
            @RequestParam(required = false, defaultValue = "") String city,
            @RequestParam(required = false, defaultValue = "") String state,
            @RequestParam(required = false, defaultValue = "") String zip,
            @RequestParam(required = false, defaultValue = "US") String country,
            @RequestParam(required = false, defaultValue = "") String couponCode,
            @AuthenticationPrincipal UserDetails principal,
            HttpSession session) {

        SessionCart cart = resolveCart(session);
        if (cart.isEmpty())
            return err("Cart is empty");

        // Compute subtotal from cart
        BigDecimal subtotal = cart.getItems().stream()
                .map(e -> e.unitPrice().multiply(BigDecimal.valueOf(e.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Resolve coupon discount
        BigDecimal discount    = BigDecimal.ZERO;
        boolean    couponValid = false;
        String     resolvedCode = null;

        if (!couponCode.isBlank()) {
            DiscountCodeService.ValidationResult val = discountCodeService.validate(couponCode, subtotal);
            if (val.valid()) {
                DiscountCode dc = val.code();
                discount = dc.getType() == DiscountCode.DiscountType.PERCENT
                        ? subtotal.multiply(dc.getValue())
                               .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                        : dc.getValue().min(subtotal).setScale(2, RoundingMode.HALF_UP);
                couponValid  = true;
                resolvedCode = dc.getCode();
            }
        }

        // Centralised pricing (P0-4 / P3-5 fix)
        CartPricing.PricingBreakdown pricing = CartPricing.compute(subtotal, discount);

        if (pricing.total().compareTo(new BigDecimal("0.50")) < 0)
            return err("Order total below minimum ($0.50)");

        // Retrieve authenticated user
        User user = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        // Build item list from cart
        List<OrderItemRequest> orderItems = cart.getItems().stream()
                .map(e -> new OrderItemRequest(e.variantId(), e.quantity()))
                .collect(Collectors.toList());

        // Build combined address
        String fullAddress = address2 != null && !address2.isBlank()
                ? address + ", " + address2 : address;

        // Step 1: Create Stripe PaymentIntent
        PaymentIntent intent;
        try {
            intent = stripeService.createPaymentIntent(pricing.total(), email);
        } catch (StripeException e) {
            log.error("[Checkout] PaymentIntent creation failed: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of("error",
                    "Payment service unavailable. Please try again."));
        }

        // Step 2: Create PENDING order and reserve stock (P0-2 / P0-3 fix)
        Order pendingOrder;
        try {
            pendingOrder = orderService.createPendingOrder(
                    user.getId(), orderItems,
                    fullName.isBlank() ? email : fullName,
                    fullAddress, city, state, zip, country,
                    resolvedCode, pricing, intent.getId());
        } catch (InsufficientStockException ex) {
            log.warn("[Checkout] Stock exhausted during order creation — PI={}", intent.getId());
            return err("One or more items ran out of stock. Please review your cart.");
        }

        // Store in session for the POST /checkout verification step
        session.setAttribute("pendingOrderId",          pendingOrder.getId());
        session.setAttribute("pendingPaymentIntentId",  intent.getId());
        if (resolvedCode != null) {
            session.setAttribute("pendingCouponCode", resolvedCode);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("clientSecret", intent.getClientSecret());
        resp.put("total",        pricing.total());
        resp.put("shipping",     pricing.shipping());
        resp.put("tax",          pricing.tax());
        resp.put("discount",     pricing.discount());
        resp.put("couponValid",  couponValid);
        return ResponseEntity.ok(resp);
    }

    // ── POST /api/discount/validate (AJAX) ────────────────────────────────────

    @PostMapping("/api/discount/validate")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> validateDiscount(
            @RequestParam String code, HttpSession session) {

        SessionCart cart = resolveCart(session);
        BigDecimal subtotal = cart.getItems().stream()
                .map(e -> e.unitPrice().multiply(BigDecimal.valueOf(e.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DiscountCodeService.ValidationResult result = discountCodeService.validate(code, subtotal);
        if (!result.valid())
            return ResponseEntity.ok(Map.of("valid", false, "error", result.error()));

        DiscountCode dc = result.code();
        BigDecimal savings = dc.getType() == DiscountCode.DiscountType.PERCENT
                ? subtotal.multiply(dc.getValue())
                       .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : dc.getValue().min(subtotal).setScale(2, RoundingMode.HALF_UP);

        return ResponseEntity.ok(Map.of(
                "valid",    true,
                "code",     dc.getCode(),
                "type",     dc.getType().name(),
                "value",    dc.getValue(),
                "savings",  savings,
                "discount", savings
        ));
    }

    // ── POST /checkout ─────────────────────────────────────────────────────────

    /**
     * Thin form-submit handler. The order already exists (PENDING); this endpoint
     * verifies the Stripe PI succeeded, transitions the order to PAID, and redirects.
     * The webhook handles the async PROCESSING transition, email, and Printful push.
     */
    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public String placeOrder(@ModelAttribute CheckoutForm form,
                             @AuthenticationPrincipal UserDetails principal,
                             HttpSession session, Model model) {

        // Retrieve the PENDING order from the session
        Long pendingOrderId = (Long) session.getAttribute("pendingOrderId");
        if (pendingOrderId == null) return "redirect:/shop";

        String paymentIntentId = form.getPaymentIntentId();
        String sessionIntentId = (String) session.getAttribute("pendingPaymentIntentId");

        if (paymentIntentId == null || paymentIntentId.isBlank()
                || !paymentIntentId.equals(sessionIntentId)) {
            log.warn("[Checkout] PI mismatch — form:{} session:{}", paymentIntentId, sessionIntentId);
            // Cancel the pending order so stock is restored
            safelyCancelOrder(pendingOrderId);
            clearPendingSession(session);
            SessionCart cart = resolveCart(session);
            addCheckoutModel(model, cart, form);
            model.addAttribute("paymentError", "Payment session expired. Please try again.");
            return "checkout";
        }

        // Verify with Stripe that the PI is in succeeded state and the amount matches
        try {
            // The order's total is the authoritative amount to verify against
            Order order = orderService.findOrderWithItemsForUser(pendingOrderId,
                    userService.findByEmail(principal.getUsername())
                               .orElseThrow().getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            PaymentIntent intent = stripeService.retrieveAndVerify(
                    paymentIntentId, order.getTotal());

            if (intent == null) {
                safelyCancelOrder(pendingOrderId);
                clearPendingSession(session);
                SessionCart cart = resolveCart(session);
                addCheckoutModel(model, cart, form);
                model.addAttribute("paymentError",
                        "Payment could not be verified. Please contact support.");
                return "checkout";
            }

        } catch (StripeException e) {
            log.error("[Checkout] Stripe verification error: {}", e.getMessage());
            SessionCart cart = resolveCart(session);
            addCheckoutModel(model, cart, form);
            model.addAttribute("paymentError",
                    "Payment verification failed. Please contact support.");
            return "checkout";
        }

        // Mark the order PAID (webhook will transition to PROCESSING asynchronously)
        orderService.markOrderPaid(pendingOrderId);

        // Clear cart and pending session attributes
        SessionCart cart = resolveCart(session);
        cart.clear();
        session.setAttribute("cart", cart);
        clearPendingSession(session);

        // Post-purchase discount code for confirmation page
        session.setAttribute("nextOrderCode",
                discountCodeService.generateNextOrderCode().getCode());

        return "redirect:/order/" + pendingOrderId + "/confirmation";
    }

    // ── GET /order/{id}/confirmation ───────────────────────────────────────────

    @GetMapping("/order/{id}/confirmation")
    @PreAuthorize("isAuthenticated()")
    public String orderConfirmation(@PathVariable Long id,
                                    @AuthenticationPrincipal UserDetails principal,
                                    HttpSession session, Model model) {
        User user = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        Order order = orderService.findOrderWithItemsForUser(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        String nextOrderCode = (String) session.getAttribute("nextOrderCode");
        session.removeAttribute("nextOrderCode");

        model.addAttribute("order",         order);
        model.addAttribute("nextOrderCode", nextOrderCode);
        return "order-confirmation";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void addCheckoutModel(Model model, SessionCart cart, CheckoutForm form) {
        model.addAttribute("cart",                 cart);
        model.addAttribute("form",                 form);
        model.addAttribute("stripePublishableKey", stripeService.getPublishableKey());
    }

    private SessionCart resolveCart(HttpSession session) {
        Object attr = session.getAttribute("cart");
        return attr instanceof SessionCart existing ? existing : new SessionCart();
    }

    private void clearPendingSession(HttpSession session) {
        session.removeAttribute("pendingOrderId");
        session.removeAttribute("pendingPaymentIntentId");
        session.removeAttribute("pendingCouponCode");
    }

    private void safelyCancelOrder(Long orderId) {
        try {
            orderService.cancelOrder(orderId);
        } catch (Exception e) {
            log.warn("[Checkout] Could not cancel pending order {}: {}", orderId, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> err(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
