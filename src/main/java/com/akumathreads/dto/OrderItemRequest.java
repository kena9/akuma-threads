package com.akumathreads.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Represents one line in a checkout request.
 *
 * @param variantId PK of the {@link com.akumathreads.model.ProductVariant} to purchase
 * @param quantity  number of units to purchase — must be between 1 and 99
 */
public record OrderItemRequest(
        @NotNull(message = "variantId is required")
        Long variantId,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 99, message = "quantity cannot exceed 99")
        int quantity
) {}
