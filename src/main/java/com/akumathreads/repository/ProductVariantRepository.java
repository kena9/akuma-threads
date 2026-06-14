package com.akumathreads.repository;

import com.akumathreads.model.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductId(Long productId);

    List<ProductVariant> findByProductIdAndStockQtyGreaterThan(Long productId, int minStock);

    /**
     * Atomically decrements stock using a conditional UPDATE that acts as an
     * optimistic concurrency guard — the WHERE clause prevents decrement below zero.
     *
     * <p>Returns the number of rows updated: 1 on success, 0 if stock was insufficient
     * (a concurrent purchase exhausted it between the service's validation read and this
     * update). The caller must treat a 0 return as a failure and roll back.
     *
     * @param variantId target variant PK
     * @param qty       units to decrement (must be positive)
     * @return 1 if decrement succeeded; 0 if stock was insufficient
     */
    @Modifying
    @Query("UPDATE ProductVariant v " +
           "SET v.stockQty = v.stockQty - :qty " +
           "WHERE v.id = :variantId AND v.stockQty >= :qty")
    int decrementStock(@Param("variantId") Long variantId, @Param("qty") int qty);

    /**
     * Increments stock — used when an order is cancelled and stock is restored.
     */
    @Modifying
    @Query("UPDATE ProductVariant v " +
           "SET v.stockQty = v.stockQty + :qty " +
           "WHERE v.id = :variantId")
    int incrementStock(@Param("variantId") Long variantId, @Param("qty") int qty);
}
