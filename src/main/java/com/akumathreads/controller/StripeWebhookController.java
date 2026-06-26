package com.akumathreads.controller;

import com.akumathreads.model.Order;
import com.akumathreads.service.DiscountCodeService;
import com.akumathreads.service.EmailService;
import com.akumathreads.service.OrderService;
import com.akumathreads.service.PrintfulService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Stripe webhook receiver — the authoritative source of truth for payment outcomes.
 *
 * <p>This endpoint is excluded from CSRF protection (Stripe cannot send our CSRF
 * token) and from authentication. Authenticity is verified instead via Stripe's
 * HMAC signature ({@code Stripe-Signature} header + {@code stripe.webhook-secret}).
 *
 * <p>Stripe retries webhooks on any non-2xx response, so we never return 4xx/5xx
 * for legitimate events — only for invalid signatures.
 *
 * <h2>Event handling (P0-2, P0-5, P1-1, P1-2 fixes)</h2>
 * <ul>
 *   <li>{@code payment_intent.succeeded} — transitions the matching order from
 *       PENDING/PAID to PROCESSING, atomically redeems any discount code, sends the
 *       order confirmation email (async), and pushes the order to Printful.</li>
 *   <li>{@code payment_intent.payment_failed} — cancels the matching PENDING order
 *       and restores stock so the customer can try again.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private final OrderService        orderService;
    private final DiscountCodeService discountCodeService;
    private final EmailService        emailService;
    private final PrintfulService     printfulService;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        // Skip processing when secret is not configured (local dev without Stripe CLI)
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("[Webhook] stripe.webhook-secret not set — event ignored " +
                     "(configure via Stripe CLI: stripe listen --forward-to localhost:8080/stripe/webhook)");
            return ResponseEntity.ok("ok");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("[Webhook] Invalid Stripe signature: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        log.info("[Webhook] Received event: {} id={}", event.getType(), event.getId());

        Optional<StripeObject> dataObj = event.getDataObjectDeserializer().getObject();

        switch (event.getType()) {

            case "payment_intent.succeeded" -> {
                if (dataObj.isPresent() && dataObj.get() instanceof PaymentIntent pi) {
                    handlePaymentSucceeded(pi);
                } else {
                    log.warn("[Webhook] Could not deserialize PaymentIntent from succeeded event {}",
                            event.getId());
                }
            }

            case "payment_intent.payment_failed" -> {
                if (dataObj.isPresent() && dataObj.get() instanceof PaymentIntent pi) {
                    handlePaymentFailed(pi);
                } else {
                    log.warn("[Webhook] Could not deserialize PaymentIntent from failed event {}",
                            event.getId());
                }
            }

            default -> log.debug("[Webhook] Unhandled event type: {}", event.getType());
        }

        // Always 200 for any other event type — Stripe must not retry unknowns
        return ResponseEntity.ok("ok");
    }

    // ── Private handlers ──────────────────────────────────────────────────────

    /**
     * Handles {@code payment_intent.succeeded}.
     *
     * <ol>
     *   <li>Transitions order PENDING/PAID → PROCESSING.</li>
     *   <li>Atomically redeems the discount code (if any) — idempotent conditional UPDATE.</li>
     *   <li>Sends the order confirmation email asynchronously (P1-1 fix).</li>
     *   <li>Pushes the order to Printful for print-and-ship fulfillment (P1-2 fix).
     *       Idempotent — skipped if {@code printfulOrderId} is already set.</li>
     * </ol>
     */
    private void handlePaymentSucceeded(PaymentIntent pi) {
        log.info("[Webhook] payment_intent.succeeded — PI={} amount={}¢",
                pi.getId(), pi.getAmountReceived());

        // Step 1: Transition to PROCESSING; get fully-loaded order (with user + items)
        Optional<Order> orderOpt = orderService.markPaidByPaymentIntent(pi.getId());

        if (orderOpt.isEmpty()) {
            log.warn("[Webhook] No order found for PI={}", pi.getId());
            return;
        }

        Order order = orderOpt.get();
        String userEmail    = order.getUser().getEmail();
        String customerName = order.getShipName() != null ? order.getShipName() : userEmail;

        // Step 2: Atomically redeem discount code (P0-5 fix)
        if (order.getCouponCode() != null) {
            boolean redeemed = discountCodeService.redeemIfAvailable(order.getCouponCode());
            log.info("[Webhook] Discount code '{}' for order {} — redeemed={}",
                    order.getCouponCode(), order.getId(), redeemed);
        }

        // Step 3: Send order confirmation email async (P1-1 fix)
        try {
            emailService.sendOrderConfirmation(
                    userEmail,
                    customerName,
                    order.getId(),
                    order.getTotal(),
                    order.getCouponCode(),
                    order.getDiscountAmount());
        } catch (Exception e) {
            log.warn("[Webhook] Failed to queue confirmation email for order {}: {}",
                    order.getId(), e.getMessage());
        }

        // Step 4: Push to Printful — idempotent (P1-2 fix)
        if (order.getPrintfulOrderId() == null) {
            try {
                String printfulId = printfulService.submitOrder(order, userEmail);
                if (printfulId != null) {
                    orderService.savePrintfulOrderId(order.getId(), printfulId);
                    log.info("[Webhook] Printful order {} created for order {}",
                            printfulId, order.getId());
                }
            } catch (Exception e) {
                log.warn("[Webhook] Printful push failed for order {}: {}",
                        order.getId(), e.getMessage());
            }
        } else {
            log.debug("[Webhook] Skipping Printful push for order {} — already submitted (id={})",
                    order.getId(), order.getPrintfulOrderId());
        }
    }

    /**
     * Handles {@code payment_intent.payment_failed}.
     * Cancels the PENDING order and restores stock so the customer can retry.
     */
    private void handlePaymentFailed(PaymentIntent pi) {
        log.info("[Webhook] payment_intent.payment_failed — PI={}", pi.getId());
        orderService.cancelByPaymentIntent(pi.getId());
    }
}
