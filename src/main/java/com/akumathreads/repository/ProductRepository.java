package com.akumathreads.repository;

import com.akumathreads.model.Product;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Product repository with N+1 prevention, dynamic filtering via Specifications,
 * and paginated shop queries.
 *
 * <p>Extending {@link JpaSpecificationExecutor} unlocks
 * {@code findAll(Specification, Pageable)} — the backbone of the shop filter system.
 * All standard methods benefit from {@code @SQLRestriction("deleted = false")} on
 * the entity, so soft-deleted products are excluded automatically.
 *
 * <p>N+1 strategy for paginated results: rather than using JOIN FETCH (which breaks
 * pagination by forcing Hibernate to load all rows into memory), we rely on
 * {@code @BatchSize(size = 30)} declared on {@code Product.variants}. When Hibernate
 * loads a page of 12 products, it issues a single {@code IN(...)} query for their
 * variant collections — effectively zero N+1 with zero pagination compromise.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long>,
                                            JpaSpecificationExecutor<Product> {

    // ── Paginated shop query (primary read path) ──────────────────────────────

    /**
     * Paginated, dynamically filtered shop listing.
     * The {@link Specification} is built by {@link ProductSpecification#withFilters}
     * and encodes keyword, category, and price range predicates.
     * {@link Pageable} carries page number, page size (12), and sort direction.
     *
     * <p>Variant loading is handled by {@code @BatchSize} — no JOIN FETCH needed here.
     *
     * @param spec     composed filter specification; use {@code Specification.where(null)}
     *                 for an unfiltered listing
     * @param pageable pagination + sort parameters
     * @return one page of matching products
     */
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    // ── N+1-safe single-product fetch (detail page) ───────────────────────────

    /**
     * Fetches one product with all variants in a single SQL JOIN FETCH.
     * Used on the product detail page where we need full variant data immediately.
     */
    @Query("SELECT p FROM Product p JOIN FETCH p.variants WHERE p.id = :productId")
    Optional<Product> findByIdWithVariants(@Param("productId") Long productId);

    // ── Non-paginated fetches (admin + seeding) ───────────────────────────────

    @EntityGraph(value = "Product.withVariants")
    @QueryHints(@QueryHint(name = "org.hibernate.cacheable", value = "true"))
    @Query("SELECT p FROM Product p WHERE p.active = true ORDER BY p.createdDate DESC")
    List<Product> findAllActiveWithVariants();

    List<Product> findByCategoryAndActiveTrue(Product.Category category);

    List<Product> findByNameContainingIgnoreCaseAndActiveTrue(String keyword);

    @QueryHints(@QueryHint(name = "org.hibernate.cacheable", value = "true"))
    List<Product> findByActiveTrueOrderByCreatedDateDesc();

    // ── Admin: bypass soft-delete filter ─────────────────────────────────────

    @Query(value = "SELECT * FROM products WHERE deleted = true ORDER BY last_modified_date DESC",
           nativeQuery = true)
    List<Product> findAllSoftDeleted();
}
