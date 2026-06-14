package com.akumathreads.repository;

import com.akumathreads.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
