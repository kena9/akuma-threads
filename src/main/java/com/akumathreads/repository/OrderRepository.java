package com.akumathreads.repository;

import com.akumathreads.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Order repository with N+1 prevention.
 *
 * <p>The core problem: loading orders for the order-history page used to fire
 * one SQL query per order to load its items, then one per item to load its
 * variant, etc. Each method below eliminates that cascade with a single
 * eagerly-joined query.
 *
 * <p>Two strategies are demonstrated side-by-side:
 * <ul>
 *   <li>JPQL {@code JOIN FETCH} — explicit, tunable, but verbose.</li>
 *   <li>{@link EntityGraph} — declarative and cleaner for simple paths,
 *       but Spring Data generates the join plan automatically.</li>
 * </ul>
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // ── Single order (admin + customer detail view) ───────────────────────────

    /**
     * Loads one order with all its items, each item's variant, and the variant's
     * product — in a single SQL query using JPQL JOIN FETCH.
     *
     * <p>EntityGraph alternative (same effect, different syntax):
     * <pre>
     * {@code @EntityGraph(attributePaths = {"items", "items.variant", "items.variant.product"})}
     * Optional<Order> findById(Long orderId);
     * </pre>
     *
     * @param orderId PK of the order to load
     * @return {@link Optional} containing the fully-loaded order, or empty if not found
     */
    @Query("SELECT DISTINCT o FROM Order o " +
           "JOIN FETCH o.items i " +
           "JOIN FETCH i.variant v " +
           "JOIN FETCH v.product " +
           "WHERE o.id = :orderId")
    Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

    // ── All orders for a user (customer order history) ────────────────────────

    /**
     * Loads all orders for a user with items eagerly fetched using an
     * {@link EntityGraph}. Spring Data generates an outer join that retrieves
     * everything in one round-trip.
     *
     * <p>Sorted newest-first at the database level to avoid in-memory sorting.
     *
     * @param userId PK of the {@link com.akumathreads.model.User}
     * @return list of fully-loaded orders, empty list if none found
     */
    @EntityGraph(attributePaths = {"items", "items.variant", "items.variant.product"})
    @Query("SELECT DISTINCT o FROM Order o WHERE o.user.id = :userId ORDER BY o.createdAt DESC")
    List<Order> findAllByUserIdWithItems(@Param("userId") Long userId);

    // ── Admin queries ─────────────────────────────────────────────────────────

    /**
     * All orders across all users, newest first. Admin dashboard use only.
     * Does NOT eagerly load items — the admin list view shows only order-level
     * data (total, status, customer name) and uses a separate detail call for items.
     */
    List<Order> findAllByOrderByCreatedAtDesc();

    /**
     * All orders for a specific user sorted newest-first.
     * Light version — no item join fetch, used for the admin user-detail panel.
     */
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

     // ── Edition / scarcity helpers ────────────────────────────────────────────

    /**
     * Total units of a product sold across all orders.
     * Used to display "Edition X of Y" on the product page.
     */
    @Query("SELECT COALESCE(SUM(i.quantity), 0) FROM OrderItem i WHERE i.variant.product.id = :productId")
    long countUnitsSoldByProductId(@Param("productId") Long productId);

    /**
     * Total units sold per product for a batch of product IDs.
     * Returns rows of [productId (Long), totalSold (Long)].
     * Products with zero sales are absent from the result.
     */
    @Query("SELECT i.variant.product.id, COALESCE(SUM(i.quantity), 0) " +
           "FROM OrderItem i " +
           "WHERE i.variant.product.id IN :ids " +
           "GROUP BY i.variant.product.id")
    List<Object[]> countUnitsSoldByProductIds(@Param("ids") List<Long> ids);

    // ── Stripe webhook lookup ─────────────────────────────────────────────────

    Optional<Order> findByPaymentIntentId(String paymentIntentId);

    /**
     * Loads a single order by Stripe PaymentIntent ID with all relations eagerly
     * fetched (user, items, variant, product). Used by the webhook to send
     * confirmation emails and push to Printful without triggering lazy-load failures.
     */
    @Query("SELECT DISTINCT o FROM Order o " +
           "JOIN FETCH o.user " +
           "JOIN FETCH o.items i " +
           "JOIN FETCH i.variant v " +
           "JOIN FETCH v.product " +
           "WHERE o.paymentIntentId = :pi")
    Optional<Order> findByPaymentIntentIdWithItems(@Param("pi") String pi);

    // ── Stale PENDING order cleanup ───────────────────────────────────────────

    /**
     * Returns orders in the given status whose {@code createdAt} is before the cutoff.
     * Used by the cleanup scheduler to expire PENDING orders abandoned by the user.
     */
    List<Order> findByStatusAndCreatedAtBefore(Order.Status status, LocalDateTime cutoff);

    // ── Abandoned cart detection ──────────────────────────────────────────────

    /**
     * Returns user IDs that placed an order within the given time window.
     * Used to exclude recent buyers from abandoned-cart recovery emails.
     */
    @Query("SELECT DISTINCT o.user.id FROM Order o WHERE o.createdAt > :since")
    List<Long> findUserIdsWithRecentOrders(@Param("since") java.time.LocalDateTime since);
}
