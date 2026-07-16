package com.akumathreads.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CartPricing} — the single source of truth for all
 * checkout arithmetic. Every dollar charged through Stripe flows through
 * {@code compute()}, so every branch here is exercised.
 */
class CartPricingTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void belowFreeShippingThreshold_chargesFlatRate() {
        CartPricing.PricingBreakdown p = CartPricing.compute(d("50.00"), BigDecimal.ZERO);
        assertEquals(d("8.99"), p.shipping());
        // taxBase = 50 + 8.99 = 58.99; tax = 4.7192 → 4.72
        assertEquals(d("4.72"), p.tax());
        assertEquals(d("63.71"), p.total());
    }

    @Test
    void atFreeShippingThreshold_shippingIsZero() {
        CartPricing.PricingBreakdown p = CartPricing.compute(d("75.00"), BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, p.shipping());
        assertEquals(d("6.00"), p.tax());
        assertEquals(d("81.00"), p.total());
    }

    @Test
    void aboveFreeShippingThreshold_shippingIsZero() {
        CartPricing.PricingBreakdown p = CartPricing.compute(d("100.00"), BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, p.shipping());
        assertEquals(d("8.00"), p.tax());
        assertEquals(d("108.00"), p.total());
    }

    @Test
    void discountReducesTaxableBase() {
        // subtotal 100, discount 20 → taxBase 80, no shipping (>=75 pre-discount)
        CartPricing.PricingBreakdown p = CartPricing.compute(d("100.00"), d("20.00"));
        assertEquals(BigDecimal.ZERO, p.shipping());
        assertEquals(d("6.40"), p.tax());
        assertEquals(d("86.40"), p.total());
    }

    @Test
    void discountLargerThanSubtotal_clampsTaxableBaseAtZero() {
        // subtotal 30, discount 50 → taxBase max(30-50,0)+8.99 = 8.99
        CartPricing.PricingBreakdown p = CartPricing.compute(d("30.00"), d("50.00"));
        assertEquals(d("8.99"), p.shipping());
        assertEquals(d("0.72"), p.tax());   // 8.99 * 0.08 = 0.7192 → 0.72
        assertEquals(d("9.71"), p.total());
    }

    @Test
    void nullDiscount_treatedAsZero() {
        CartPricing.PricingBreakdown withNull = CartPricing.compute(d("60.00"), null);
        CartPricing.PricingBreakdown withZero = CartPricing.compute(d("60.00"), BigDecimal.ZERO);
        assertEquals(withZero.total(), withNull.total());
        assertEquals(BigDecimal.ZERO, withNull.discount());
    }

    @Test
    void zeroSubtotal_stillChargesShippingAndTaxOnShipping() {
        CartPricing.PricingBreakdown p = CartPricing.compute(d("0.00"), BigDecimal.ZERO);
        assertEquals(d("8.99"), p.shipping());
        assertEquals(d("0.72"), p.tax());
        assertEquals(d("9.71"), p.total());
    }

    @Test
    void taxRoundsHalfUpToCents() {
        // subtotal 10.06 + 8.99 shipping = 19.05; tax 1.524 → 1.52
        CartPricing.PricingBreakdown p = CartPricing.compute(d("10.06"), BigDecimal.ZERO);
        assertEquals(d("1.52"), p.tax());
        // subtotal 10.13 + 8.99 = 19.12; tax 1.5296 → 1.53
        CartPricing.PricingBreakdown p2 = CartPricing.compute(d("10.13"), BigDecimal.ZERO);
        assertEquals(d("1.53"), p2.tax());
    }

    @Test
    void breakdownEchoesInputs() {
        CartPricing.PricingBreakdown p = CartPricing.compute(d("42.00"), d("2.00"));
        assertEquals(d("42.00"), p.subtotal());
        assertEquals(d("2.00"), p.discount());
    }
}
