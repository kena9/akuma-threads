package com.akumathreads.service;

import com.akumathreads.dto.OrderItemRequest;
import com.akumathreads.exception.InsufficientStockException;
import com.akumathreads.model.Order;
import com.akumathreads.model.OrderItem;
import com.akumathreads.model.Product;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.model.User;
import com.akumathreads.pricing.CartPricing;
import com.akumathreads.repository.OrderRepository;
import com.akumathreads.repository.ProductVariantRepository;
import com.akumathreads.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService} — the class that owns the two guarantees
 * the whole payment flow depends on:
 *
 * <ol>
 *   <li><b>Stock safety</b> — inventory is decremented atomically and never
 *       oversold, even when a concurrent purchase races between the validation
 *       read and the conditional UPDATE.</li>
 *   <li><b>Webhook idempotency</b> — Stripe retries webhooks, so every
 *       transition must be a safe no-op when replayed.</li>
 * </ol>
 *
 * Pure Mockito, no Spring context — runs in milliseconds.
 */
class OrderServiceTest {

    private OrderRepository          orderRepository;
    private ProductVariantRepository variantRepository;
    private UserRepository           userRepository;
    private OrderService             service;

    private User user;
    private ProductVariant variant1;   // id=1, $20.00, stock 10
    private ProductVariant variant2;   // id=2, $35.00, stock 5

    private static final CartPricing.PricingBreakdown PRICING =
            CartPricing.compute(new BigDecimal("75.00"), BigDecimal.ZERO);

    @BeforeEach
    void setUp() {
        orderRepository   = mock(OrderRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        userRepository    = mock(UserRepository.class);
        service = new OrderService(orderRepository, variantRepository, userRepository);

        user = mock(User.class);

        variant1 = variantWith(1L, "20.00", 10);
        variant2 = variantWith(2L, "35.00", 5);
    }

    private static ProductVariant variantWith(Long id, String price, int stock) {
        Product product = mock(Product.class);
        when(product.getPrice()).thenReturn(new BigDecimal(price));
        ProductVariant v = new ProductVariant();
        v.setId(id);
        v.setStockQty(stock);
        v.setProduct(product);
        return v;
    }

    // ── createPendingOrder ────────────────────────────────────────────────────

    @Test
    void createPendingOrder_happyPath_reservesStockAndSnapshotsPricing() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(variantRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(variant1, variant2));
        when(variantRepository.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Order order = service.createPendingOrder(
                7L,
                List.of(new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1)),
                "Ken E", "1 Main St", "Atlanta", "GA", "30301", "US",
                null, PRICING, "pi_123");

        assertEquals(Order.Status.PENDING, order.getStatus());
        assertEquals("pi_123", order.getPaymentIntentId());
        assertEquals(PRICING.total(),    order.getTotal());
        assertEquals(PRICING.shipping(), order.getShippingCost());
        assertEquals(PRICING.tax(),      order.getTaxAmount());
        assertNull(order.getCouponCode());

        // Stock reserved exactly once per line, with the requested quantity
        verify(variantRepository).decrementStock(1L, 2);
        verify(variantRepository).decrementStock(2L, 1);

        // Line items snapshot the live product price
        assertEquals(2, order.getItems().size());
        OrderItem line1 = order.getItems().get(0);
        assertEquals(new BigDecimal("20.00"), line1.getUnitPrice());
        assertEquals(2, line1.getQuantity());
        assertSame(order, line1.getOrder());
    }

    @Test
    void createPendingOrder_withCoupon_persistsCodeAndDiscount() {
        CartPricing.PricingBreakdown discounted =
                CartPricing.compute(new BigDecimal("100.00"), new BigDecimal("10.00"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(variantRepository.findAllById(List.of(1L))).thenReturn(List.of(variant1));
        when(variantRepository.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Order order = service.createPendingOrder(
                7L, List.of(new OrderItemRequest(1L, 1)),
                "Ken", "1 Main St", "Atlanta", "GA", "30301", "US",
                "SAVE10", discounted, "pi_abc");

        assertEquals("SAVE10", order.getCouponCode());
        assertEquals(new BigDecimal("10.00"), order.getDiscountAmount());
    }

    @Test
    void createPendingOrder_insufficientStockAtValidation_throwsBeforeAnyMutation() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        variant1.setStockQty(1);
        when(variantRepository.findAllById(List.of(1L))).thenReturn(List.of(variant1));

        assertThrows(InsufficientStockException.class, () ->
                service.createPendingOrder(
                        7L, List.of(new OrderItemRequest(1L, 2)),
                        "Ken", "1 Main St", "Atlanta", "GA", "30301", "US",
                        null, PRICING, "pi_x"));

        // Validation failed BEFORE step 3 — no decrement, no order persisted
        verify(variantRepository, never()).decrementStock(anyLong(), anyInt());
        verify(orderRepository,   never()).save(any());
    }

    @Test
    void createPendingOrder_concurrentPurchaseWinsRace_conditionalUpdateThrows() {
        // Validation read sees stock=10, but a concurrent order drains it before
        // our UPDATE runs — the WHERE stockQty >= qty guard returns 0 rows.
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(variantRepository.findAllById(List.of(1L))).thenReturn(List.of(variant1));
        when(variantRepository.decrementStock(1L, 2)).thenReturn(0);

        assertThrows(InsufficientStockException.class, () ->
                service.createPendingOrder(
                        7L, List.of(new OrderItemRequest(1L, 2)),
                        "Ken", "1 Main St", "Atlanta", "GA", "30301", "US",
                        null, PRICING, "pi_x"));

        verify(orderRepository, never()).save(any());
    }

    @Test
    void createPendingOrder_unknownVariant_throwsEntityNotFound() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(variantRepository.findAllById(List.of(99L))).thenReturn(List.of());

        assertThrows(EntityNotFoundException.class, () ->
                service.createPendingOrder(
                        7L, List.of(new OrderItemRequest(99L, 1)),
                        "Ken", "1 Main St", "Atlanta", "GA", "30301", "US",
                        null, PRICING, "pi_x"));
    }

    // ── markOrderPaid (browser confirms) ─────────────────────────────────────

    @Test
    void markOrderPaid_pendingOrder_transitionsToPaid() {
        Order order = new Order();
        order.setStatus(Order.Status.PENDING);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        service.markOrderPaid(5L);

        assertEquals(Order.Status.PAID, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void markOrderPaid_webhookAlreadyProcessed_isNoOp() {
        // Webhook fired first and moved the order to PROCESSING;
        // the browser's form submit must not regress it to PAID.
        Order order = new Order();
        order.setStatus(Order.Status.PROCESSING);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        service.markOrderPaid(5L);

        assertEquals(Order.Status.PROCESSING, order.getStatus());
        verify(orderRepository, never()).save(any());
    }

    // ── markPaidByPaymentIntent (webhook success) ─────────────────────────────

    @Test
    void markPaidByPaymentIntent_pending_movesToProcessing() {
        Order order = new Order();
        order.setStatus(Order.Status.PENDING);
        when(orderRepository.findByPaymentIntentIdWithItems("pi_1"))
                .thenReturn(Optional.of(order));

        Optional<Order> result = service.markPaidByPaymentIntent("pi_1");

        assertTrue(result.isPresent());
        assertEquals(Order.Status.PROCESSING, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void markPaidByPaymentIntent_replayedWebhook_isIdempotent() {
        // Stripe redelivered payment_intent.succeeded; order is already PROCESSING.
        Order order = new Order();
        order.setStatus(Order.Status.PROCESSING);
        when(orderRepository.findByPaymentIntentIdWithItems("pi_1"))
                .thenReturn(Optional.of(order));

        Optional<Order> result = service.markPaidByPaymentIntent("pi_1");

        // Order is still returned (so the caller's own idempotency checks can
        // run, e.g. printfulOrderId != null) but nothing is re-written.
        assertTrue(result.isPresent());
        assertEquals(Order.Status.PROCESSING, order.getStatus());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markPaidByPaymentIntent_unknownPi_returnsEmpty() {
        when(orderRepository.findByPaymentIntentIdWithItems("pi_ghost"))
                .thenReturn(Optional.empty());
        assertTrue(service.markPaidByPaymentIntent("pi_ghost").isEmpty());
        verify(orderRepository, never()).save(any());
    }

    // ── cancelByPaymentIntent (webhook failure) ───────────────────────────────

    @Test
    void cancelByPaymentIntent_pendingOrder_restoresStockAndCancels() {
        Order stub = new Order();
        stub.setId(5L);
        stub.setStatus(Order.Status.PENDING);
        when(orderRepository.findByPaymentIntentId("pi_fail"))
                .thenReturn(Optional.of(stub));

        Order full = new Order();
        full.setId(5L);
        full.setStatus(Order.Status.PENDING);
        OrderItem item = new OrderItem();
        item.setVariant(variant1);
        item.setQuantity(3);
        full.setItems(new java.util.ArrayList<>(List.of(item)));
        when(orderRepository.findByIdWithItems(5L)).thenReturn(Optional.of(full));

        service.cancelByPaymentIntent("pi_fail");

        verify(variantRepository).incrementStock(1L, 3);
        assertEquals(Order.Status.CANCELLED, full.getStatus());
        verify(orderRepository).save(full);
    }

    @Test
    void cancelByPaymentIntent_afterSuccess_mustNotRestoreStock() {
        // A late/replayed payment_failed webhook arriving after the order already
        // succeeded must NOT cancel it or hand back reserved inventory.
        Order order = new Order();
        order.setId(5L);
        order.setStatus(Order.Status.PROCESSING);
        when(orderRepository.findByPaymentIntentId("pi_1"))
                .thenReturn(Optional.of(order));

        service.cancelByPaymentIntent("pi_1");

        assertEquals(Order.Status.PROCESSING, order.getStatus());
        verify(variantRepository, never()).incrementStock(anyLong(), anyInt());
        verify(orderRepository,   never()).save(any());
    }

    // ── cancelOrder (admin / cleanup scheduler) ───────────────────────────────

    @Test
    void cancelOrder_shippedOrder_rejected() {
        Order order = new Order();
        order.setStatus(Order.Status.SHIPPED);
        when(orderRepository.findByIdWithItems(9L)).thenReturn(Optional.of(order));

        assertThrows(IllegalStateException.class, () -> service.cancelOrder(9L));
        verify(variantRepository, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void cancelOrder_pendingOrder_restoresEveryLine() {
        Order order = new Order();
        order.setStatus(Order.Status.PENDING);
        OrderItem a = new OrderItem(); a.setVariant(variant1); a.setQuantity(2);
        OrderItem b = new OrderItem(); b.setVariant(variant2); b.setQuantity(1);
        order.setItems(new java.util.ArrayList<>(List.of(a, b)));
        when(orderRepository.findByIdWithItems(9L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Order cancelled = service.cancelOrder(9L);

        assertEquals(Order.Status.CANCELLED, cancelled.getStatus());
        verify(variantRepository).incrementStock(1L, 2);
        verify(variantRepository).incrementStock(2L, 1);
    }

    // ── savePrintfulOrderId ───────────────────────────────────────────────────

    @Test
    void savePrintfulOrderId_persistsIdForWebhookIdempotency() {
        Order order = new Order();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        service.savePrintfulOrderId(5L, "pf_777");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertEquals("pf_777", captor.getValue().getPrintfulOrderId());
    }
}
