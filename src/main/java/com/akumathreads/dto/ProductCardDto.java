package com.akumathreads.dto;

import com.akumathreads.model.Product;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable snapshot of a product card — safe to cache.
 *
 * <p>Unlike the {@link Product} entity, this DTO carries no JPA proxy or lazy
 * collection. It is computed inside an open session (see {@link
 * com.akumathreads.service.ProductService#findFiltered}) so that
 * {@code getTotalStock()} and {@code getDefaultVariantId()} resolve before
 * the session closes. Caching the DTO instead of the entity prevents
 * {@code LazyInitializationException} on cache hits.
 *
 * <p>Implements {@link Serializable} so the cache can be backed by a
 * distributed store (Redis, etc.) without extra configuration later.
 */
@Getter
@AllArgsConstructor
public class ProductCardDto implements Serializable {

    private Long           id;
    private String         name;
    private BigDecimal     price;
    private String         imageUrl;
    private Product.Category category;
    private int            totalStock;
    private Long           defaultVariantId;
    private LocalDateTime  createdDate;

    /**
     * Factory — must be called while the JPA session is still open so that
     * accessing {@code product.getVariants()} does not throw.
     */
    public static ProductCardDto from(Product p) {
        return new ProductCardDto(
                p.getId(),
                p.getName(),
                p.getPrice(),
                p.getImageUrl(),
                p.getCategory(),
                p.getTotalStock(),
                p.getDefaultVariantId(),
                p.getCreatedDate()
        );
    }
}
