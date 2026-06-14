package com.akumathreads.controller;

import com.akumathreads.dto.CheckoutForm;
import com.akumathreads.dto.OrderItemRequest;
import com.akumathreads.exception.InsufficientStockException;
import com.akumathreads.model.Order;
import com.akumathreads.model.SessionCart;
import com.akumathreads.model.User;
import com.akumathreads.service.OrderService;
import com.akumathreads.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the complete checkout flow:
 * <ol>
 *   <li>GET /checkout — cart review + shipping form</li>
 *   <li>POST /checkout — validate, place order, clear cart</li>
 *   <li>GET /order/{id}/confirmation — order summary</li>
 * </ol>
 *
 * <p>All routes require authentication — enforced both here via
 * {@code @PreAuthorize} and at the Spring Security filter chain level
 * in {@link com.akumathreads.config.SecurityConfig}.
 */
@Controller
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class CheckoutController {

    private final OrderService orderService;
    private final UserService userService;

    // ── GET /checkout ──────────────────────────────────────────────────────────

    @GetMapping("/checkout")
    public String checkoutPage(@AuthenticationPrincipal UserDetails principal,
                               HttpSession session,
                               Model model) {
        SessionCart cart = resolveCart(session);
        if (cart.isEmpty()) {
            return "redirect:/shop";
        }

        // Pre-fill email from the authenticated user
        String email = principal.getUsername();
        CheckoutForm form = new CheckoutForm();
        form.setEmail(email);

        model.addAttribute("cart", cart);
        model.addAttribute("form", form);
        return "checkout";
    }

    // ── POST /checkout ─────────────────────────────────────────────────────────

    @PostMapping("/checkout")
    public String placeOrder(@Valid CheckoutForm form,
                             BindingResult bindingResult,
                             @AuthenticationPrincipal UserDetails principal,
                             HttpSession session,
                             Model model) {

        SessionCart cart = resolveCart(session);
        if (cart.isEmpty()) {
            return "redirect:/shop";
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("cart", cart);
            model.addAttribute("form", form);
            return "checkout";
        }

        User user = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        // Convert session cart entries to OrderItemRequest records
        List<OrderItemRequest> items = cart.getItems().stream()
                .map(e -> new OrderItemRequest(e.variantId(), e.quantity()))
                .collect(Collectors.toList());

        // Build full shipping address for the service (combines address + address2)
        String fullAddress = form.getAddress2() != null && !form.getAddress2().isBlank()
                ? form.getAddress() + ", " + form.getAddress2()
                : form.getAddress();

        try {
            Order order = orderService.placeOrder(
                    user.getId(), items,
                    form.getFullName(), fullAddress,
                    form.getCity(), form.getState(), form.getZip()
            );
            cart.clear();
            session.setAttribute("cart", cart);
            return "redirect:/order/" + order.getId() + "/confirmation";

        } catch (InsufficientStockException ex) {
            model.addAttribute("cart", cart);
            model.addAttribute("form", form);
            model.addAttribute("stockError",
                    "One or more items in your cart ran out of stock. Please review and try again.");
            return "checkout";
        }
    }

    // ── GET /order/{id}/confirmation ───────────────────────────────────────────

    @GetMapping("/order/{id}/confirmation")
    public String orderConfirmation(@PathVariable Long id,
                                    @AuthenticationPrincipal UserDetails principal,
                                    Model model) {
        User user = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        Order order = orderService.findOrderWithItemsForUser(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Order not found"));

        model.addAttribute("order", order);
        return "order-confirmation";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private SessionCart resolveCart(HttpSession session) {
        Object attr = session.getAttribute("cart");
        if (attr instanceof SessionCart existing) {
            return existing;
        }
        return new SessionCart();
    }
}
