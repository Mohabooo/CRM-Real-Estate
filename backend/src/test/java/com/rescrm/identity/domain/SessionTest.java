package com.rescrm.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A session")
class SessionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final OffsetDateTime NOON =
            OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration IDLE = Duration.ofHours(8);
    private static final Duration ABSOLUTE = Duration.ofDays(7);

    private static Session issued() {
        return Session.issue(TENANT, USER, "hash", NOON, IDLE, ABSOLUTE);
    }

    @Nested
    @DisplayName("when issued")
    class WhenIssued {

        @Test
        @DisplayName("expires after the idle timeout and, at the latest, the absolute lifetime")
        void carries_both_expiries() {
            Session session = issued();

            assertThat(session.expiresAt()).isEqualTo(NOON.plus(IDLE));
            assertThat(session.absoluteExpiresAt()).isEqualTo(NOON.plus(ABSOLUTE));
            assertThat(session.lastSeenAt()).isEqualTo(NOON);
            assertThat(session.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("refuses an idle timeout longer than the absolute lifetime")
        void idle_cannot_exceed_absolute() {
            assertThatThrownBy(() -> Session.issue(TENANT, USER, "hash", NOON,
                    Duration.ofDays(30), Duration.ofDays(7)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("absolute lifetime");
        }

        @Test
        @DisplayName("refuses a blank token hash, which would be a session anybody could present")
        void requires_a_token_hash() {
            assertThatThrownBy(() -> Session.issue(TENANT, USER, "  ", NOON, IDLE, ABSOLUTE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("while it is being used")
    class WhileUsed {

        @Test
        @DisplayName("is live before its idle expiry and not after")
        void liveness_follows_the_idle_expiry() {
            Session session = issued();

            assertThat(session.isLiveAt(NOON.plusHours(7))).isTrue();
            assertThat(session.isLiveAt(NOON.plus(IDLE).plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("slides its idle expiry forward when touched")
        void touch_slides_the_expiry() {
            Session session = issued();

            boolean changed = session.touch(NOON.plusHours(2), IDLE);

            assertThat(changed).isTrue();
            assertThat(session.expiresAt()).isEqualTo(NOON.plusHours(2).plus(IDLE));
            assertThat(session.lastSeenAt()).isEqualTo(NOON.plusHours(2));
        }

        @Test
        @DisplayName("never slides past the absolute ceiling, however long it is kept warm")
        void touch_is_capped_by_the_absolute_expiry() {
            Session session = issued();

            // Used once an hour for the whole week — exactly the pattern a stolen cookie
            // would show if somebody wanted to keep it alive.
            for (int hour = 1; hour <= 24 * 8; hour++) {
                session.touch(NOON.plusHours(hour), IDLE);
            }

            assertThat(session.expiresAt())
                    .as("a session kept warm must still end")
                    .isEqualTo(session.absoluteExpiresAt());
            assertThat(session.isLiveAt(NOON.plus(ABSOLUTE).plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("is not touched once it has already expired")
        void an_expired_session_cannot_be_revived_by_use() {
            Session session = issued();

            boolean changed = session.touch(NOON.plus(IDLE).plusMinutes(1), IDLE);

            assertThat(changed).isFalse();
            assertThat(session.expiresAt()).isEqualTo(NOON.plus(IDLE));
        }
    }

    @Nested
    @DisplayName("when revoked")
    class WhenRevoked {

        @Test
        @DisplayName("stops being live immediately, whatever its expiry says")
        void revocation_beats_the_expiry() {
            Session session = issued();

            session.revoke(NOON.plusHours(1), "Signed out");

            assertThat(session.isLiveAt(NOON.plusHours(2))).isFalse();
            assertThat(session.revokedReason()).isEqualTo("Signed out");
        }

        @Test
        @DisplayName("always records why, because the reasons are different facts")
        void revocation_requires_a_reason() {
            Session session = issued();

            assertThatThrownBy(() -> session.revoke(NOON, "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("keeps the first reason when revoked twice")
        void revoking_twice_is_a_no_op() {
            Session session = issued();
            session.revoke(NOON.plusHours(1), "User deactivated");

            session.revoke(NOON.plusHours(2), "Signed out");

            assertThat(session.revokedAt()).isEqualTo(NOON.plusHours(1));
            assertThat(session.revokedReason()).isEqualTo("User deactivated");
        }
    }
}
