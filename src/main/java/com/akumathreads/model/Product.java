package com.akumathreads.model;

import com.akumathreads.entity.BaseAuditEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.List;

/**
 * Product entity with soft delete and JPA auditing.
 *
 * <p><strong>Soft-delete strategy — why both {@code @SQLRestriction} and {@code @Filter}?</strong>
 * <ul>
 *   <li>{@link SQLRestriction} (Hibernate 6 replacement for deprecated {@code @Where})
 *       is a <em>static</em> default: every Spring Data query method automatically
 *       appends {@code AND deleted = false}, so soft-deleted products never appear in
 *       normal customer-facing queries. It cannot be overridden at runtime.</li>
 *   <li>{@link Filter} is a <em>dynamic</em> Hibernate filter: an admin service can
 *       explicitly enable it with {@code session.enableFilter("deletedProductFilter").setParameter("isDeleted", true)}
 *       to query <em>only</em> soft-deleted products (e.g., the admin "trash" view)
 *       while ignoring the static restriction. This two-layer approach gives us a
 *       safe default for all reads AND a controlled escape hatch for admin operations.</li>
 * </ul>
 *
 * <p>{@link SQLDelete} rewrites the JPA {@code DELETE} SQL to a soft-delete UPDATE,
 * so calling {@code productRepository.delete(product)} never physically removes a row.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor

// Soft delete — rewrites DELETE to an UPDATE that sets deleted = true
@SQLDelete(sql = "UPDATE products SET deleted = true, last_modified_date = NOW() WHERE id = ?")

// Static default restriction: all Spring Data queries exclude deleted rows automatically
@SQLRestriction("deleted = false")

// Dynamic filter: allows admin queries to explicitly target deleted products
@FilterDef(
    name = "deletedProductFilter",
    parameters = @ParamDef(name = "isDeleted", type = Boolean.class)
)
@Filter(name = "deletedProductFilter", condition = "deleted = :isDeleted")

// Named entity graph — used by ProductRepository.findAllActiveWithVariants()
@NamedEntityGraph(
    name = "Product.withVariants",
    attributeNodes = @NamedAttributeNode("variants")
)
public class Product extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Positive
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    private String imageUrl;

    /** Soft-deleted flag. Set by @SQLDelete; never toggled directly in application code. */
    @Column(nullable = false)
    private boolean deleted = false;

    /**
     * Legacy convenience field preserved for backward compatibility.
     * Prefer soft-delete ({@code deleted}) over toggling {@code active} directly.
     */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * @BatchSize batches variant loading across all products in a Page result.
     * When 12 products are loaded, Hibernate issues ONE IN(...) query for their
     * variants instead of 12 separate SELECT queries — solves N+1 without
     * breaking Pageable pagination (which JOIN FETCH would break).
     */
    @org.hibernate.annotations.BatchSize(size = 30)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductVariant> variants;

    // ── Computed helpers ──────────────────────────────────────────────────────

    /**
     * Total stock units across all variants.
     * Used by the product card fragment for badge logic and button state.
     * Not persisted — computed from the loaded variants collection.
     * Callers must ensure variants are eagerly loaded (use findByIdWithVariants).
     */
    public int getTotalStock() {
        if (variants == null) return 0;
        return variants.stream().mapToInt(ProductVariant::getStockQty).sum();
    }

    /**
     * Returns the ID of the first in-stock variant, or the first variant's ID if
     * none are in stock. Used by the product card "Add to Cart" button to pre-select
     * a default SKU. Returns {@code null} if the product has no variants.
     */
    public Long getDefaultVariantId() {
        if (variants == null || variants.isEmpty()) return null;
        return variants.stream()
                .filter(v -> v.getStockQty() > 0)
                .findFirst()
                .map(ProductVariant::getId)
                .orElse(variants.get(0).getId());
    }

    // ── Enum ─────────────────────────────────────────────────────────────────

    public enum Category {
        HOODIE, SHIRT, ACCESSORY, OTHER
    }
}
