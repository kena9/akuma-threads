package com.akumathreads.service;

import com.akumathreads.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cleanup scheduler for abandoned PENDING orders.
 *
 * <p>When a customer reaches the payment step but never completes it (e.g. closes the
 * tab, card is declined before the webhook fires, or a Stripe error prevents the
 * {@code payment_intent.payment_failed} webhook from arriving), the order stays
 * PENDING indefinitely and the stock remains reserved.
 *
 * <p>This scheduler runs every 5 minutes and cancels any PENDING order older than
 * 30 minutes, restoring the reserved stock for each line item.
 *
 * <p>{@code @EnableScheduling} is declared on {@code AkumaThreadsApplication}
 * and applies application-wide.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingOrderCleanupService {

    /** Maximum age of a PENDING order before it is considered abandoned. */
    private static final int STALE_MINUTES = 30;

    private final OrderService orderService;

    /**
     * Runs every 5 minutes. Cancels PENDING orders older than {@link #STALE_MINUTES}
     * and restores their reserved stock.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void cancelStalePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<Order> stale = orderService.findStalePendingOrders(cutoff);

        if (stale.isEmpty()) return;

        log.info("[Cleanup] Found {} stale PENDING order(s) older than {} minutes",
                stale.size(), STALE_MINUTES);

        for (Order order : stale) {
            try {
                orderService.cancelOrder(order.getId());
                log.info("[Cleanup] Cancelled stale order {} (created {})",
                        order.getId(), order.getCreatedAt());
            } catch (Exception e) {
                log.warn("[Cleanup] Failed to cancel stale order {}: {}",
                        order.getId(), e.getMessage());
            }
        }
    }
}
