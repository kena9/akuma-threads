package com.akumathreads.repository;

import com.akumathreads.model.DiscountCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    List<DiscountCode> findAllByOrderByCreatedAtDesc();

    List<DiscountCode> findByActiveTrue();

    @Modifying
    @Query("UPDATE DiscountCode d SET d.usedCount = d.usedCount + 1 WHERE d.code = :code")
    void incrementUsedCount(@Param("code") String code);

    /**
     * Atomically increments {@code usedCount} only when the code is still usable:
     * active, not expired, and below its usage limit.
     *
     * <p>Returns 1 if the redemption succeeded, 0 if the code was exhausted, expired,
     * or inactive. The conditional UPDATE prevents a check-then-act race where two
     * concurrent checkouts try to redeem the last use of the same code (P0-5 fix).
     */
    @Modifying
    @Query("UPDATE DiscountCode d SET d.usedCount = d.usedCount + 1 " +
           "WHERE d.code = :code " +
           "AND d.active = true " +
           "AND (d.usageLimit IS NULL OR d.usedCount < d.usageLimit) " +
           "AND (d.expiresAt IS NULL OR d.expiresAt > :now)")
    int redeemIfAvailable(@Param("code") String code, @Param("now") LocalDateTime now);
}
