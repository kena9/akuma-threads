package com.akumathreads.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for checkout pricing arithmetic.
 *
 * <p>All constants (free-shipping threshold, shipping rate, tax rate) live here.
 * Call {@link #compute(BigDecimal, BigDecimal)} from any layer that needs a breakdown —
 * {@code CheckoutController} (for the PI amount) and {@code OrderService}
 * (for persisting the Order). Eliminates the duplicate magic numbers that previously
 * lived in both classes (P0-4 / P3-5 fix).
 *
 * <p>This is a pure utility class: no Spring beans, no I/O, trivially unit-testable.
 */
public final class CartPricing {

    /** Minimum subtotal that qualifies for free shipping. */
    public static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("75.00");

    /** Flat shipping rate when subtotal is below the free-shipping threshold. */
    public static final BigDecimal SHIPPING_RATE           = new BigDecimal("8.99");

    /** Tax rate applied to (subtotal − discount + shipping). */
    public static final BigDecimal TAX_RATE                = new BigDecimal("0.08");

    /**
     * Immutable snapshot of a full pricing calculation.
     *
     * @param subtotal  pre-discount, pre-shipping item total
     * @param discount  coupon discount applied (&gt;= 0)
     * @param shipping  flat shipping charge ($0 when subtotal &ge; FREE_SHIPPING_THRESHOLD)
     * @param tax       8% tax on (subtotal − discount + shipping), rounded to cents
     * @param total     final charge amount (taxBase + tax)
     */
    public record PricingBreakdown(
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal shipping,
            BigDecimal tax,
            BigDecimal total
    ) {}

    /**
     * Computes a full pricing breakdown from the cart subtotal and any applied discount.
     *
     * <p>Tax is calculated on the post-discount, post-shipping taxable base.
     * The discount cannot reduce the taxable base below zero (clamped via {@code max(0)}).
     *
     * @param subtotal       cart subtotal — must be non-null and &ge; 0
     * @param discountAmount coupon discount; {@code null} is treated as zero
     * @return an immutable {@link PricingBreakdown}
     */
    public static PricingBreakdown compute(BigDecimal subtotal, BigDecimal discountAmount) {
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal shipping = subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO : SHIPPING_RATE;
        BigDecimal taxBase  = subtotal.subtract(discount).max(BigDecimal.ZERO).add(shipping);
        BigDecimal tax      = taxBase.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total    = taxBase.add(tax).setScale(2, RoundingMode.HALF_UP);
        return new PricingBreakdown(subtotal, discount, shipping, tax, total);
    }

    private CartPricing() {}
}
