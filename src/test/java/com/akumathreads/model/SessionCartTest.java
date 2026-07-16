package com.akumathreads.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SessionCart} — merge behaviour, monetary arithmetic,
 * and the reprice() sync used by checkout to prevent stale-price charges.
 */
class SessionCartTest {

    private SessionCart cart;

    @BeforeEach
    void setUp() {
        cart = new SessionCart();
    }

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void addSameVariantTwice_mergesQuantities() {
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 1);
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 2);
        assertEquals(1, cart.getItems().size());
        assertEquals(3, cart.totalItemCount());
        assertEquals(d("105.00"), cart.subtotal());
    }

    @Test
    void differentVariants_keptAsSeparateLines() {
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 1);
        cart.addOrUpdate(2L, "Oni Tee", "L", d("35.00"), 1);
        assertEquals(2, cart.getItems().size());
        assertEquals(2, cart.totalItemCount());
    }

    @Test
    void remove_deletesLine_andIsNoOpWhenAbsent() {
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 1);
        cart.remove(1L);
        assertTrue(cart.isEmpty());
        cart.remove(99L); // no exception
        assertTrue(cart.isEmpty());
    }

    @Test
    void reprice_updatesUnitPriceAndSubtotal() {
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 2);
        cart.reprice(1L, d("40.00"));
        assertEquals(d("80.00"), cart.subtotal());
        assertEquals(2, cart.totalItemCount()); // quantity untouched
    }

    @Test
    void reprice_isNoOpForAbsentVariantOrNullPrice() {
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 1);
        cart.reprice(99L, d("40.00"));
        cart.reprice(1L, null);
        assertEquals(d("35.00"), cart.subtotal());
    }

    @Test
    void reprice_preservesInsertionOrder() {
        cart.addOrUpdate(1L, "First", "M", d("10.00"), 1);
        cart.addOrUpdate(2L, "Second", "M", d("20.00"), 1);
        cart.reprice(1L, d("15.00"));
        SessionCart.CartEntry first = cart.getItems().iterator().next();
        assertEquals("First", first.productName());
        assertEquals(d("15.00"), first.unitPrice());
    }

    @Test
    void subtotal_neverLosesCents() {
        cart.addOrUpdate(1L, "A", "M", d("19.99"), 3);
        cart.addOrUpdate(2L, "B", "L", d("0.01"), 1);
        assertEquals(d("59.98"), cart.subtotal());
    }

    @Test
    void nullUnitPrice_lineTotalIsZero() {
        SessionCart.CartEntry entry = new SessionCart.CartEntry(1L, "X", "M", null, 5);
        assertEquals(BigDecimal.ZERO, entry.lineTotal());
    }

    @Test
    void clear_emptiesCart() {
        cart.addOrUpdate(1L, "Oni Tee", "M", d("35.00"), 1);
        cart.clear();
        assertTrue(cart.isEmpty());
        assertEquals(BigDecimal.ZERO.setScale(2), cart.subtotal());
    }
}
