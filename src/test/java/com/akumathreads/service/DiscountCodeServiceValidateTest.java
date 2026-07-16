package com.akumathreads.service;

import com.akumathreads.model.DiscountCode;
import com.akumathreads.repository.DiscountCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DiscountCodeService#validate} — the gate every coupon
 * passes through before it can reduce a charge. Uses a mocked
 * {@link DiscountCode} so the tests pin the service's decision logic without
 * coupling to entity field internals.
 */
class DiscountCodeServiceValidateTest {

    private DiscountCodeRepository repo;
    private DiscountCodeService    service;

    @BeforeEach
    void setUp() {
        repo    = mock(DiscountCodeRepository.class);
        service = new DiscountCodeService(repo);
    }

    private DiscountCode usableCode() {
        DiscountCode dc = mock(DiscountCode.class);
        when(dc.isUsable()).thenReturn(true);
        return dc;
    }

    @Test
    void blankCode_failsWithPrompt() {
        assertFalse(service.validate("   ", new BigDecimal("50")).valid());
        assertFalse(service.validate(null,  new BigDecimal("50")).valid());
    }

    @Test
    void unknownCode_failsAsNotFound() {
        when(repo.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());

        DiscountCodeService.ValidationResult result =
                service.validate("NOPE", new BigDecimal("50"));

        assertFalse(result.valid());
        assertEquals("Code not found.", result.error());
    }

    @Test
    void expiredOrExhaustedCode_failsAsUnusable() {
        DiscountCode dc = mock(DiscountCode.class);
        when(dc.isUsable()).thenReturn(false);
        when(repo.findByCodeIgnoreCase("OLD")).thenReturn(Optional.of(dc));

        assertFalse(service.validate("OLD", new BigDecimal("50")).valid());
    }

    @Test
    void subtotalBelowMinimum_failsAndNamesTheMinimum() {
        DiscountCode dc = usableCode();
        when(dc.getMinOrderValue()).thenReturn(new BigDecimal("40.00"));
        when(repo.findByCodeIgnoreCase("BIG40")).thenReturn(Optional.of(dc));

        DiscountCodeService.ValidationResult result =
                service.validate("BIG40", new BigDecimal("39.99"));

        assertFalse(result.valid());
        assertTrue(result.error().contains("40"));
    }

    @Test
    void subtotalExactlyAtMinimum_passes() {
        DiscountCode dc = usableCode();
        when(dc.getMinOrderValue()).thenReturn(new BigDecimal("40.00"));
        when(repo.findByCodeIgnoreCase("BIG40")).thenReturn(Optional.of(dc));

        assertTrue(service.validate("BIG40", new BigDecimal("40.00")).valid());
    }

    @Test
    void codeIsTrimmedAndCaseInsensitive() {
        DiscountCode dc = usableCode();
        when(dc.getMinOrderValue()).thenReturn(null);
        when(repo.findByCodeIgnoreCase("save10")).thenReturn(Optional.of(dc));

        DiscountCodeService.ValidationResult result =
                service.validate("  save10  ", new BigDecimal("50"));

        assertTrue(result.valid());
        assertSame(dc, result.code());
    }
}
