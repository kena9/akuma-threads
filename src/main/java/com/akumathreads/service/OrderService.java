package com.akumathreads.service;

import com.akumathreads.dto.OrderItemRequest;
import com.akumathreads.exception.InsufficientStockException;
import com.akumathreads.model.Order;
import com.akumathreads.model.OrderItem;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.model.User;
import com.akumathreads.pricing.CartPricing;
import com.akumathreads.repository.OrderRepository;
import com.akumathreads.repository.ProductVariantRepository;
import com.akumathreads.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for order placement, lookup, and status management.
 *
 * <p>Default transaction mode is {@code readOnly = true} applied at class level —
 * Hibernate skips dirty-checking on reads and the JDBC driver can route to a
 * read replica if one is configured.
 *
 * <p>Every write method overrides with {@code readOnly = false, rollbackFor = Exception.class}.
 *
 * <h2>Order lifecycle (P0-2 / P0-3 fix)</h2>
 * <pre>
 *   POST /checkout/payment-intent  →  createPendingOrder()  →  PENDING
 *   POST /checkout (form submit)   →  markOrderPaid()       →  PAID
 *   Stripe webhook succeeded       →  markPaidByPaymentIntent() → PROCESSING
 *   Stripe webhook failed          →  cancelByPaymentIntent()   → CANCELLED + stock restored
 *   Cleanup scheduler (30 min)     →  cancelOrder()             → CANCELLED + stock restored
 * </pre>
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository          orderRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository           userRepository;

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Creates a PENDING order with stock reserved and all pricing fields set.
     *
     * <p>This is called at PaymentIntent-creation time, before the customer
     * enters their card details. Stock is decremented here so the inventory
     * is held for the duration of the payment window. If payment fails, the
     * webhook handler or cleanup scheduler restores the stock.
     *
     * <p>Transaction contract:
     * <ol>
     *   <li>Bulk-fetch all requested variants in a single IN query.</li>
     *   <li>Validate each variant's stock <em>before</em> any mutation.</li>
     *   <li>Decrement stock atomically per variant (WHERE stockQty &ge; qty).</li>
     *   <li>Persist the Order with status=PENDING and the full pricing breakdown.</li>
     * </ol>
     *
     * @param userId         PK of the purchasing user
     * @param items          variant + quantity pairs from the cart
     * @param shipName       shipping recipient name
     * @param shipAddress    street address (may include address2)
     * @param shipCity       city
     * @param shipState      state/province code
     * @param shipZip        postal code
     * @param shipCountry    ISO 2-letter country code (e.g. "US")
     * @param couponCode     applied discount code, or null
     * @param pricing        complete pricing breakdown from {@link CartPricing#compute}
     * @param paymentIntentId Stripe PI id (already created before this call)
     * @return the persisted PENDING {@link Order}
     * @throws InsufficientStockException if any requested quantity exceeds available stock
     * @throws EntityNotFoundException    if the user or any variant does not exist
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Order createPendingOrder(Long userId,
                                    List<OrderItemRequest> items,
                                    String shipName,
                                    String shipAddress,
                                    String shipCity,
                                    String shipState,
                                    String shipZip,
                                    String shipCountry,
                                    String couponCode,
                                    CartPricing.PricingBreakdown pricing,
                                    String paymentIntentId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        // Step 1: Bulk-fetch all variants in one query
        List<Long> variantIds = items.stream().map(OrderItemRequest::variantId).toList();
        Map<Long, ProductVariant> variantMap = variantRepository.findAllById(variantIds)
                .stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));

        for (OrderItemRequest item : items) {
            if (!variantMap.containsKey(item.variantId()))
                throw new EntityNotFoundException("Variant not found: " + item.variantId());
        }

        // Step 2: Validate stock before any mutation
        for (OrderItemRequest item : items) {
            ProductVariant variant = variantMap.get(item.variantId());
            if (variant.getStockQty() < item.quantity()) {
                throw new InsufficientStockException(
                        variant.getId(), item.quantity(), variant.getStockQty());
            }
        }

        // Step 3: Atomic stock decrement (concurrency guard at query level)
        for (OrderItemRequest item : items) {
            int rows = variantRepository.decrementStock(item.variantId(), item.quantity());
            if (rows == 0) throw new InsufficientStockException(item.variantId(), item.quantity(), 0);
        }

        // Step 4: Build Order with full pricing snapshot (P0-4 fix)
        Order order = new Order();
        order.setUser(user);
        order.setStatus(Order.Status.PENDING);
        order.setPaymentIntentId(paymentIntentId);
        order.setShipName(shipName);
        order.setShipAddress(shipAddress);
        order.setShipCity(shipCity);
        order.setShipState(shipState);
        order.setShipZip(shipZip);
        order.setShipCountry(shipCountry);
        order.setShippingCost(pricing.shipping());
        order.setTaxAmount(pricing.tax());
        order.setTotal(pricing.total());

        if (couponCode != null && !couponCode.isBlank()) {
            order.setCouponCode(couponCode);
            order.setDiscountAmount(pricing.discount());
        }

        // Build order items
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderItemRequest itemRequest : items) {
            ProductVariant variant = variantMap.get(itemRequest.variantId());
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setVariant(variant);
            orderItem.setQuantity(itemRequest.quantity());
            orderItem.setUnitPrice(variant.getProduct().getPrice());
            orderItems.add(orderItem);
        }
        order.setItems(orderItems);

        Order saved = orderRepository.save(order);
        log.info("[Order] Created PENDING order {} for user={}, total=${}, PI={}",
                saved.getId(), userId, pricing.total(), paymentIntentId);
        return saved;
    }

    /**
     * Transitions a PENDING order to PAID after the customer's browser confirms
     * payment with Stripe. Called from POST /checkout.
     *
     * <p>Idempotent: if the order is already PAID or PROCESSING (webhook fired first),
     * this is a no-op.
     *
     * @param orderId PK of the order to transition
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public void markOrderPaid(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            if (order.getStatus() == Order.Status.PENDING) {
                order.setStatus(Order.Status.PAID);
                orderRepository.save(order);
                log.info("[Order] Order {} marked PAID", orderId);
            }
        });
    }

    /**
     * Called by the Stripe webhook when {@code payment_intent.succeeded} fires.
     * Transitions the order from PENDING or PAID to PROCESSING (ready for fulfillment).
     *
     * <p>Returns the fully-loaded order (with user, items, variants, products) so the
     * webhook handler can immediately send the confirmation email and push to Printful.
     *
     * @param paymentIntentId Stripe PI id from the webhook event
     * @return the PROCESSING order with all relations loaded, or empty if not found
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Optional<Order> markPaidByPaymentIntent(String paymentIntentId) {
        Optional<Order> found = orderRepository.findByPaymentIntentIdWithItems(paymentIntentId);
        found.ifPresent(order -> {
            if (order.getStatus() == Order.Status.PAID
                    || order.getStatus() == Order.Status.PENDING) {
                order.setStatus(Order.Status.PROCESSING);
                orderRepository.save(order);
                log.info("[Order] Order {} marked PROCESSING via webhook PI={}",
                        order.getId(), paymentIntentId);
            }
        });
        if (found.isEmpty()) {
            log.warn("[Order] No order found for PI={}", paymentIntentId);
        }
        return found;
    }

    /**
     * Called by the Stripe webhook when {@code payment_intent.payment_failed} fires.
     * Cancels the PENDING order and restores stock so the customer can try again.
     *
     * @param paymentIntentId Stripe PI id from the webhook event
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public void cancelByPaymentIntent(String paymentIntentId) {
        orderRepository.findByPaymentIntentId(paymentIntentId).ifPresent(order -> {
            if (order.getStatus() == Order.Status.PENDING) {
                // Fetch with items for stock restoration
                orderRepository.findByIdWithItems(order.getId()).ifPresent(fullOrder -> {
                    for (OrderItem item : fullOrder.getItems()) {
                        variantRepository.incrementStock(item.getVariant().getId(), item.getQuantity());
                    }
                    fullOrder.setStatus(Order.Status.CANCELLED);
                    orderRepository.save(fullOrder);
                    log.info("[Order] Order {} CANCELLED via payment_failed webhook PI={}",
                            fullOrder.getId(), paymentIntentId);
                });
            }
        });
    }

    /**
     * Saves a Printful order ID back to the order after a successful Printful push.
     * Used by the webhook handler to make Printful submission idempotent on retries.
     *
     * @param orderId         our internal order PK
     * @param printfulOrderId the ID returned by Printful's API
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public void savePrintfulOrderId(Long orderId, String printfulOrderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setPrintfulOrderId(printfulOrderId);
            orderRepository.save(order);
        });
    }

    /**
     * Admin-initiated or cleanup-scheduler cancellation.
     * Only PENDING and PROCESSING orders may be cancelled.
     * Restores stock for each line item.
     *
     * @param orderId the order to cancel
     * @throws EntityNotFoundException if the order does not exist
     * @throws IllegalStateException   if the order is SHIPPED, DELIVERED, or already CANCELLED
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        if (order.getStatus() == Order.Status.SHIPPED
                || order.getStatus() == Order.Status.DELIVERED
                || order.getStatus() == Order.Status.CANCELLED) {
            throw new IllegalStateException(
                    "Cannot cancel order in status: " + order.getStatus());
        }

        for (OrderItem item : order.getItems()) {
            variantRepository.incrementStock(item.getVariant().getId(), item.getQuantity());
        }

        order.setStatus(Order.Status.CANCELLED);
        return orderRepository.save(order);
    }

    /**
     * Admin: update order status to any target value.
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Order updateStatus(Long orderId, Order.Status newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
        order.setStatus(newStatus);
        return orderRepository.save(order);
    }

    // ── Read operations ──────────────────────────────────────────────────────

    /**
     * Fetches a single order with all items eagerly loaded, verifying ownership.
     * Prevents IDOR by filtering on userId.
     */
    public Optional<Order> findOrderWithItemsForUser(Long orderId, Long userId) {
        return orderRepository.findByIdWithItems(orderId)
                .filter(order -> order.getUser().getId().equals(userId));
    }

    /**
     * Fetches all orders for a user with items eagerly loaded — safe for the
     * order history page without N+1 queries.
     */
    public List<Order> findAllOrdersForUser(Long userId) {
        return orderRepository.findAllByUserIdWithItems(userId);
    }

    /** Admin: all orders across all users, newest first (summary data only). */
    public List<Order> findAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Admin: single order with full item detail, regardless of owner. */
    public Optional<Order> findOrderWithItemsForAdmin(Long orderId) {
        return orderRepository.findByIdWithItems(orderId);
    }

    /**
     * Returns PENDING orders older than the given cutoff — used by the cleanup
     * scheduler to expire abandoned checkouts and restore their stock.
     */
    public List<Order> findStalePendingOrders(java.time.LocalDateTime cutoff) {
        return orderRepository.findByStatusAndCreatedAtBefore(Order.Status.PENDING, cutoff);
    }
}
