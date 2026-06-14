package com.akumathreads.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/cart/add}.
 *
 * @param productId  PK of the {@link com.akumathreads.model.Product} being added
 * @param variantId  PK of the {@link com.akumathreads.model.ProductVariant} (size/SKU)
 * @param quantity   units to add — must be between 1 and 99 inclusive
 */
public record AddToCartRequest(
        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "variantId is required")
        Long variantId,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 99, message = "quantity cannot exceed 99")
        int quantity
) {}
