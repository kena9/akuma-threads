package com.akumathreads.service;

import com.akumathreads.dto.OrderItemRequest;
import com.akumathreads.exception.InsufficientStockException;
import com.akumathreads.model.Order;
import com.akumathreads.model.OrderItem;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.model.User;
import com.akumathreads.repository.OrderRepository;
import com.akumathreads.repository.ProductVariantRepository;
import com.akumathreads.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for order placement, lookup, and status management.
 *
 * <p>Default transaction mode is {@code readOnly = true} (applied at class level)
 * so all read methods participate in read-only transactions — Hibernate skips
 * dirty-checking on reads, and the JDBC driver can route to a read replica if
 * one is configured.
 *
 * <p>Every write method overrides with {@code readOnly = false, rollbackFor = Exception.class}.
 * The {@code rollbackFor = Exception.class} addition catches both unchecked exceptions
 * (Spring's default) AND any checked exceptions that might propagate through the call stack.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Places a new order for the given user.
     *
     * <p>Transaction contract:
     * <ol>
     *   <li>Bulk-fetch all requested variants in a single IN query — avoids N+1 on
     *       the validation loop.</li>
     *   <li>Validate each variant's stock <em>before</em> any mutation. If any item
     *       fails, throws {@link InsufficientStockException} and the entire transaction
     *       rolls back with zero stock decrements applied.</li>
     *   <li>Decrement stock using a conditional UPDATE per variant that guards against
     *       concurrent purchases ({@code WHERE stockQty >= qty}). If the DB UPDATE
     *       affects 0 rows, a concurrent buyer exhausted the stock between step 2 and
     *       step 3 — we throw and roll back.</li>
     *   <li>Persist the {@link Order} and its {@link OrderItem} children.</li>
     * </ol>
     *
     * <p>If any exception (checked or unchecked) is thrown at any step, Spring rolls
     * back the entire transaction — no partial stock decrements or orphaned orders.
     *
     * @param userId      PK of the purchasing user
     * @param items       list of variant + quantity pairs
     * @param shipName    shipping recipient name
     * @param shipAddress street address
     * @param shipCity    city
     * @param shipState   state/province abbreviation
     * @param shipZip     postal code
     * @return the persisted {@link Order} with generated ID
     * @throws InsufficientStockException if any requested quantity exceeds available stock
     * @throws EntityNotFoundException    if the user or any variant does not exist
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Order placeOrder(Long userId,
                            List<OrderItemRequest> items,
                            String shipName,
                            String shipAddress,
                            String shipCity,
                            String shipState,
                            String shipZip) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        // ── Step 1: Bulk-fetch all variants in one query ──────────────────────
        List<Long> variantIds = items.stream()
                .map(OrderItemRequest::variantId)
                .toList();

        Map<Long, ProductVariant> variantMap = variantRepository.findAllById(variantIds)
                .stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));

        // Ensure every requested variant actually exists
        for (OrderItemRequest item : items) {
            if (!variantMap.containsKey(item.variantId())) {
                throw new EntityNotFoundException("Variant not found: " + item.variantId());
            }
        }

        // ── Step 2: Validate stock before any mutation ────────────────────────
        for (OrderItemRequest item : items) {
            ProductVariant variant = variantMap.get(item.variantId());
            if (variant.getStockQty() < item.quantity()) {
                throw new InsufficientStockException(
                        variant.getId(),
                        item.quantity(),
                        variant.getStockQty());
            }
        }

        // ── Step 3: Atomic stock decrement (concurrency guard at query level) ──
        // The WHERE clause (stockQty >= qty) means a 0-row result signals that a
        // concurrent transaction bought the last units between steps 2 and 3.
        for (OrderItemRequest item : items) {
            int rowsUpdated = variantRepository.decrementStock(item.variantId(), item.quantity());
            if (rowsUpdated == 0) {
                throw new InsufficientStockException(item.variantId(), item.quantity(), 0);
            }
        }

        // ── Step 4: Persist Order + OrderItems ───────────────────────────────
        Order order = new Order();
        order.setUser(user);
        order.setStatus(Order.Status.PENDING);
        order.setShipName(shipName);
        order.setShipAddress(shipAddress);
        order.setShipCity(shipCity);
        order.setShipState(shipState);
        order.setShipZip(shipZip);

        List<OrderItem> orderItems  = new ArrayList<>();
        BigDecimal      runningTotal = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : items) {
            ProductVariant variant = variantMap.get(itemRequest.variantId());

            // Snapshot the price at purchase time so order history is accurate
            // even after future product price changes.
            BigDecimal linePrice = variant.getProduct().getPrice()
                    .multiply(BigDecimal.valueOf(itemRequest.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setVariant(variant);
            orderItem.setQuantity(itemRequest.quantity());
            orderItem.setUnitPrice(variant.getProduct().getPrice());
            orderItems.add(orderItem);

            runningTotal = runningTotal.add(linePrice);
        }

        order.setItems(orderItems);
        order.setTotal(runningTotal.setScale(2, RoundingMode.HALF_UP));

        return orderRepository.save(order);
    }

    /**
     * Soft-transitions an order to CANCELLED status and restores stock.
     * Only PENDING and PROCESSING orders may be cancelled.
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

        // Restore stock for each item
        for (OrderItem item : order.getItems()) {
            variantRepository.incrementStock(item.getVariant().getId(), item.getQuantity());
        }

        order.setStatus(Order.Status.CANCELLED);
        return orderRepository.save(order);
    }

    /**
     * Admin: update order status to any target value.
     *
     * @param orderId   PK of the order to update
     * @param newStatus the target {@link Order.Status}
     * @return the updated order
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
     * Fetches a single order with all items eagerly loaded.
     * Verifies that the order belongs to {@code userId} to prevent IDOR.
     *
     * @param orderId PK of the order
     * @param userId  PK of the requesting user
     * @return {@link Optional} with the order, or empty if not found or not owned by this user
     */
    public Optional<Order> findOrderWithItemsForUser(Long orderId, Long userId) {
        return orderRepository.findByIdWithItems(orderId)
                .filter(order -> order.getUser().getId().equals(userId));
    }

    /**
     * Fetches all orders for a user with items eagerly loaded — safe for the
     * order history page without triggering N+1 queries.
     *
     * @param userId PK of the user
     * @return list of orders newest-first, empty list if none
     */
    public List<Order> findAllOrdersForUser(Long userId) {
        return orderRepository.findAllByUserIdWithItems(userId);
    }

    /**
     * Admin: fetches all orders across all users, newest first.
     * Items are NOT eagerly loaded — the admin list view shows summary data only.
     *
     * @return all orders
     */
    public List<Order> findAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Admin: fetches a single order with full item detail, regardless of owner.
     *
     * @param orderId PK of the order
     * @return {@link Optional} with the order, or empty if not found
     */
    public Optional<Order> findOrderWithItemsForAdmin(Long orderId) {
        return orderRepository.findByIdWithItems(orderId);
    }
}
