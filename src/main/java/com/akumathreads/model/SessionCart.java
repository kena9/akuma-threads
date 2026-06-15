package com.akumathreads.model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable session-backed shopping cart.
 *
 * <p>Keyed by {@code variantId} so that the same variant appearing twice merges
 * quantities rather than creating a duplicate entry. Uses insertion-order iteration
 * ({@link LinkedHashMap}) so the cart page renders items in add-order.
 *
 * <p>Stored in {@link jakarta.servlet.http.HttpSession} under the key
 * {@code "cart"} by {@link com.akumathreads.controller.CartRestController}.
 */
public class SessionCart implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Map<Long, CartEntry> items = new LinkedHashMap<>();

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Adds {@code quantity} units of {@code variantId} to the cart.
     * If the variant is already present, quantities are summed.
     */
    public void addOrUpdate(Long variantId,
                            String productName,
                            String sizeName,
                            BigDecimal unitPrice,
                            int quantity) {
        items.merge(
            variantId,
            new CartEntry(variantId, productName, sizeName, unitPrice, quantity),
            (existing, incoming) -> new CartEntry(
                variantId,
                productName,
                sizeName,
                unitPrice,
                existing.quantity() + incoming.quantity()
            )
        );
    }

    /**
     * Removes the entry for the given {@code variantId}. No-op if absent.
     */
    public void remove(Long variantId) {
        items.remove(variantId);
    }

    /** Removes all entries. */
    public void clear() {
        items.clear();
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Total number of individual units across all entries. */
    public int totalItemCount() {
        return items.values().stream()
                .mapToInt(CartEntry::quantity)
                .sum();
    }

    /**
     * Subtotal across all entries, rounded to 2 decimal places using HALF_UP.
     * Never uses {@code double} or {@code float} for monetary arithmetic.
     */
    public BigDecimal subtotal() {
        return items.values().stream()
                .map(e -> e.unitPrice()
                            .multiply(BigDecimal.valueOf(e.quantity()))
                            .setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Unmodifiable view of cart entries in insertion order. */
    public Collection<CartEntry> getItems() {
        return Collections.unmodifiableCollection(items.values());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    // ── Inner record ─────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of a single cart line.
     *
     * @param variantId   the {@link ProductVariant} PK
     * @param productName display name captured at add-time (survives product renames)
     * @param unitPrice   price captured at add-time (not live-synced)
     * @param quantity    number of units in this line
     */
    public record CartEntry(
            Long variantId,
            String productName,
            String sizeName,
            BigDecimal unitPrice,
            int quantity
    ) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Line total — unit price × quantity, rounded to 2dp. */
        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity))
                            .setScale(2, RoundingMode.HALF_UP);
        }
    }
}
