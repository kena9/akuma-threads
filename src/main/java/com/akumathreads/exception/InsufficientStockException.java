package com.akumathreads.exception;

/**
 * Thrown by {@link com.akumathreads.service.OrderService#placeOrder} when the
 * requested quantity for a variant exceeds current stock at the time of purchase.
 *
 * <p>Extends {@link RuntimeException} so Spring's default transaction rollback
 * behaviour triggers without requiring explicit {@code rollbackFor} configuration
 * (though {@code rollbackFor = Exception.class} is still set on the service method
 * to catch any checked exceptions that leak through).
 *
 * <p>Carries the variant PK and both the requested and available quantities so
 * the caller can build a user-friendly error message without re-querying the database.
 */
public class InsufficientStockException extends RuntimeException {

    private final Long variantId;
    private final int requested;
    private final int available;

    public InsufficientStockException(Long variantId, int requested, int available) {
        super(String.format(
            "Insufficient stock for variant %d: requested %d but only %d available.",
            variantId, requested, available));
        this.variantId = variantId;
        this.requested = requested;
        this.available = available;
    }

    public Long getVariantId() {
        return variantId;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
