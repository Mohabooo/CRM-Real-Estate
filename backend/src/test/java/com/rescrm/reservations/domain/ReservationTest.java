package com.rescrm.reservations.domain;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hold's own rules (doc 16 section 10, doc 18 section 3).
 *
 * <p>Doc 16's framing drives most of these: a reservation is informal and frequently
 * abandoned, and it is supposed to expire harmlessly. So the interesting assertions are
 * about what it refuses to do once it has ended, and about expiry being a fact of the clock
 * rather than somebody's decision.
 */
@DisplayName("Reservation")
class ReservationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID LEAD = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    private static Reservation hold() {
        return Reservation.place(TENANT, UNIT, LEAD, null, AGENT, null, NOW, NOW.plusDays(7));
    }

    private static Reservation confirmedHold() {
        Reservation reservation = hold();
        reservation.confirm(NOW);
        return reservation;
    }

    @Nested
    @DisplayName("when placed")
    class WhenPlaced {

        @Test
        @DisplayName("starts pending, so the unit is spoken for but not yet withheld")
        void starts_pending() {
            Reservation reservation = hold();

            assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
            assertThat(reservation.status().isActive()).isTrue();
            assertThat(reservation.status().holdsTheUnit())
                    .as("E4-S1: the unit becomes reserved only on confirmation")
                    .isFalse();
        }

        @Test
        @DisplayName("records where its expiry started, so an extension limit has a datum")
        void remembers_its_original_expiry() {
            Reservation reservation = hold();
            assertThat(reservation.originalExpiresAt()).isEqualTo(reservation.expiresAt());
            assertThat(reservation.extensionCount()).isZero();
        }

        @Test
        @DisplayName("is held for exactly one of a lead or a customer")
        void exactly_one_party() {
            // Both would make every downstream join ambiguous about who the hold is for;
            // neither would make it unattributable.
            assertThatThrownBy(() -> Reservation.place(TENANT, UNIT, LEAD, UUID.randomUUID(),
                    AGENT, null, NOW, NOW.plusDays(7)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one");

            assertThatThrownBy(() -> Reservation.place(TENANT, UNIT, null, null, AGENT, null,
                    NOW, NOW.plusDays(7)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("must expire after it starts")
        void expiry_follows_the_start() {
            assertThatThrownBy(() -> Reservation.place(TENANT, UNIT, LEAD, null, AGENT, null,
                    NOW, NOW.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Reservation.place(TENANT, UNIT, LEAD, null, AGENT, null,
                    NOW, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the state machine")
    class StateMachine {

        @Test
        @DisplayName("pending -> confirmed")
        void the_documented_path() {
            Reservation reservation = hold();
            reservation.confirm(NOW);

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.status().holdsTheUnit()).isTrue();
        }

        @Test
        @DisplayName("a released hold is closed with its reason and the time it ended")
        void release_records_why() {
            Reservation reservation = confirmedHold();
            reservation.release("customer changed their mind", NOW.plusDays(1));

            assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(reservation.closedReason()).isEqualTo("customer changed their mind");
            assertThat(reservation.closedAt()).isEqualTo(NOW.plusDays(1));
        }

        @Test
        @DisplayName("a release needs a reason")
        void release_requires_a_reason() {
            Reservation reservation = confirmedHold();

            assertThatThrownBy(() -> reservation.release("  ", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(reservation.status())
                    .as("a rejected release must leave the hold standing")
                    .isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("a pending hold is cancelled, not released — nothing was ever withheld")
        void pending_cancels() {
            Reservation reservation = hold();
            reservation.cancel("duplicate enquiry", NOW);

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(ReservationStatus.CANCELLED.holdsTheUnit()).isFalse();
        }

        @Test
        @DisplayName("a confirmed hold cannot be cancelled; it is released")
        void confirmed_does_not_cancel() {
            assertThatThrownBy(() -> confirmedHold().cancel("changed my mind", NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("nothing can be done to a hold that has ended")
        void an_ended_hold_is_frozen() {
            Reservation reservation = confirmedHold();
            reservation.release("gone", NOW);

            assertThatThrownBy(() -> reservation.confirm(NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> reservation.release("again", NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> reservation.extendTo(NOW.plusDays(30), NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> reservation.expire(NOW.plusDays(30)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a confirmed hold converts, and records when it closed")
        void conversion() {
            // This test used to assert the opposite: that no method on the entity could
            // reach CONVERTED, because doc 18's precondition is "deal created from it" and
            // Epic 4 could not create one. Epic 5 can, so the guard has done its job and is
            // replaced by the behaviour it was holding the place for.
            Reservation reservation = confirmedHold();
            reservation.convert(NOW);

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CONVERTED);
            assertThat(reservation.closedAt()).isEqualTo(NOW);
            assertThat(reservation.closedReason())
                    .as("this hold ended by succeeding; there is nothing to explain")
                    .isNull();
        }

        @Test
        @DisplayName("a hold that was never confirmed cannot convert")
        void pending_holds_do_not_convert() {
            // Doc 18 section 3 lists confirmed -> converted and nothing else into that
            // state. A pending hold never withheld its unit, so converting one would claim
            // a sale of something the system had not reserved.
            assertThatThrownBy(() -> hold().convert(NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("and neither can one that expired or was released")
        void closed_holds_do_not_convert() {
            Reservation released = confirmedHold();
            released.release("Customer withdrew", NOW);
            assertThatThrownBy(() -> released.convert(NOW))
                    .isInstanceOf(IllegalStateException.class);

            Reservation expired = confirmedHold();
            expired.expire(NOW.plusDays(7));
            assertThatThrownBy(() -> expired.convert(NOW.plusDays(7)))
                    .as("E4-S2: an expired reservation cannot convert")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("converting twice is refused")
        void conversion_is_terminal() {
            Reservation reservation = confirmedHold();
            reservation.convert(NOW);
            assertThatThrownBy(() -> reservation.convert(NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("is a fact of the clock, not a decision")
        void cannot_be_expired_early() {
            Reservation reservation = confirmedHold();

            assertThatThrownBy(() -> reservation.expire(NOW.plusDays(1)))
                    .as("expiring early would take a unit off an agent who still had it")
                    .isInstanceOf(IllegalStateException.class);

            reservation.expire(NOW.plusDays(7));
            assertThat(reservation.status()).isEqualTo(ReservationStatus.EXPIRED);
        }

        @Test
        @DisplayName("happens the instant the expiry passes, not a moment after")
        void expires_exactly_on_time() {
            Reservation reservation = confirmedHold();
            assertThat(reservation.hasExpiredBy(NOW.plusDays(7).minusSeconds(1))).isFalse();
            assertThat(reservation.hasExpiredBy(NOW.plusDays(7))).isTrue();
        }

        @Test
        @DisplayName("a hold that has already ended is not 'expired' as well")
        void an_ended_hold_does_not_expire_again() {
            Reservation reservation = confirmedHold();
            reservation.release("gone", NOW);
            assertThat(reservation.hasExpiredBy(NOW.plusYears(1))).isFalse();
        }

        @Test
        @DisplayName("an expired hold cannot be confirmed or extended afterwards")
        void expired_is_final() {
            Reservation reservation = hold();

            assertThatThrownBy(() -> reservation.confirm(NOW.plusDays(8)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expired");
        }
    }

    @Nested
    @DisplayName("extension (E4-S3)")
    class Extension {

        @Test
        @DisplayName("moves the expiry out and counts itself")
        void extending_counts() {
            Reservation reservation = confirmedHold();
            reservation.extendTo(NOW.plusDays(10), NOW.plusDays(1));

            assertThat(reservation.expiresAt()).isEqualTo(NOW.plusDays(10));
            assertThat(reservation.extensionCount()).isEqualTo(1);
            assertThat(reservation.originalExpiresAt())
                    .as("the datum the limit is measured from must not move")
                    .isEqualTo(NOW.plusDays(7));
        }

        @Test
        @DisplayName("measures the total from the original expiry, not from the last one")
        void the_limit_is_cumulative() {
            Reservation reservation = confirmedHold();
            reservation.extendTo(NOW.plusDays(9), NOW.plusDays(1));
            reservation.extendTo(NOW.plusDays(11), NOW.plusDays(2));

            // Measured from "now" each time, two two-day extensions would each look small
            // and a manager could walk the hold forward indefinitely. Measured from the
            // original, this is four days and countable against the tenant's limit.
            assertThat(reservation.extensionSoFar().toDays()).isEqualTo(4);
            assertThat(reservation.extensionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("cannot move the expiry backwards")
        void extending_only_goes_forward() {
            Reservation reservation = confirmedHold();

            assertThatThrownBy(() -> reservation.extendTo(NOW.plusDays(3), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> reservation.extendTo(reservation.expiresAt(), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("cannot revive a hold that has already expired")
        void cannot_extend_past_the_end() {
            Reservation reservation = confirmedHold();

            assertThatThrownBy(() -> reservation.extendTo(NOW.plusDays(30), NOW.plusDays(8)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expired");
        }
    }

    @Nested
    @DisplayName("deposit (E4-S4)")
    class Deposit {

        @Test
        @DisplayName("records the amount and says nothing about what it means")
        void records_the_amount_only() {
            Reservation reservation = hold();
            reservation.recordDeposit(Money.of("50000.00", CurrencyCode.EGP), true);

            assertThat(reservation.depositAmount().toPlainString()).isEqualTo("50000.00");
            assertThat(reservation.isDepositReceived()).isTrue();

            // The same number means tenant cash under own-inventory and a developer
            // confirmation under brokered. CollectionPolicy decides; this row does not.
            boolean anyMeaningOnTheEntity =
                    java.util.Arrays.stream(Reservation.class.getMethods())
                            .anyMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT)
                                    .contains("meaning"));
            assertThat(anyMeaningOnTheEntity).isFalse();
        }

        @Test
        @DisplayName("refuses a zero or negative amount")
        void a_deposit_is_positive() {
            Reservation reservation = hold();
            assertThatThrownBy(() -> reservation.recordDeposit(
                    Money.zero(CurrencyCode.EGP), true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("cannot be marked received when there is no amount")
        void received_needs_an_amount() {
            Reservation reservation = hold();
            reservation.recordDeposit(null, true);

            assertThat(reservation.depositAmount()).isNull();
            assertThat(reservation.isDepositReceived())
                    .as("a deposit received with no amount is not a fact anyone can act on")
                    .isFalse();
        }
    }
}
