package com.rescrm.reservations.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the expiry sweep on a timer (E4-S2).
 *
 * <p>Deliberately thin: it owns the schedule and nothing else, so the sweep itself stays a
 * plain method that a test can call and assert on rather than something that only happens
 * when a clock ticks.
 *
 * <p>Off by default in the test profile. An integration test that had a background sweep
 * expiring its fixtures mid-assertion would fail intermittently and for reasons nobody would
 * find quickly — the tests drive {@link ReservationExpirySweep#sweep()} directly instead,
 * which also lets them control the clock.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "crm.reservations.expiry-sweep.enabled",
        havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final ReservationExpirySweep sweep;

    public ReservationExpiryScheduler(ReservationExpirySweep sweep) {
        this.sweep = sweep;
    }

    /**
     * Fixed delay rather than fixed rate: if a pass runs long, the next one waits instead of
     * piling up behind it. A minute of lateness on a seven-day hold costs nothing; two sweeps
     * racing each other over the same rows costs lock contention and confusing audit trails.
     */
    @Scheduled(fixedDelayString = "${crm.reservations.expiry-sweep.interval-ms:300000}",
            initialDelayString = "${crm.reservations.expiry-sweep.initial-delay-ms:60000}")
    public void run() {
        ExpirySweepResult result = sweep.sweep();
        if (result.expiredCount() > 0 || !result.isClean()) {
            log.info("Reservation expiry sweep: {} tenants, {} expired, {} units released, "
                            + "{} tenants failed",
                    result.tenantsSwept(), result.expiredCount(), result.unitsReleased().size(),
                    result.failedTenants().size());
        }
    }
}
