package com.akumathreads.repository;

import com.akumathreads.model.Product;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Static factory for composable JPA Specifications used by the shop filter system.
 *
 * <p>Each method returns a {@link Specification} that can be composed with
 * {@code Specification.where(...).and(...)} for clean, type-safe dynamic queries
 * without string-concatenated JPQL or native SQL.
 *
 * <p>The {@code @SQLRestriction("deleted = false")} on {@link Product} automatically
 * appends the soft-delete predicate to every query — no need to add it here.
 */
public final class ProductSpecification {

    private ProductSpecification() {}

    /**
     * Builds a single composed Specification from all active filter parameters.
     * Null or blank values are ignored — only active filters contribute predicates.
     *
     * @param keyword  case-insensitive substring matched against name AND description
     * @param category exact category match; null means all categories
     * @param minPrice minimum price inclusive; null means no lower bound
     * @param maxPrice maximum price inclusive; null means no upper bound
     * @return composed Specification safe to pass directly to a paginated repository call
     */
    public static Specification<Product> withFilters(String keyword,
                                                      Product.Category category,
                                                      BigDecimal minPrice,
                                                      BigDecimal maxPrice) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Only active (non-soft-deleted) products — belt-and-suspenders alongside @SQLRestriction
            predicates.add(cb.isTrue(root.get("active")));

            // Keyword: matches name OR description (case-insensitive)
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.strip().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")),        pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                ));
            }

            // Category: exact enum match
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }

            // Price range
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
