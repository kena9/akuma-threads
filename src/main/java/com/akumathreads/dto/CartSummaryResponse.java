package com.akumathreads.dto;

import java.math.BigDecimal;

/**
 * JSON response returned by every {@code /api/cart/**} write endpoint.
 *
 * @param itemCount total number of units across all cart lines (for navbar badge)
 * @param subtotal  cart total in USD, always 2 decimal places (HALF_UP)
 * @param message   human-readable status message for the toast notification
 */
public record CartSummaryResponse(
        int itemCount,
        BigDecimal subtotal,
        String message
) {}
