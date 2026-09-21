package com.rescrm.identity.domain;

import com.rescrm.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Invitation")
class InvitationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);

    private Invitation pendingInvitation() {
        return Invitation.issue(TENANT, "invitee@example.com", Role.SALES_AGENT, BRANCH,
                "hash", NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("is pending before expiry and expired after it")
    void status_is_derived_from_the_clock() {
        Invitation invitation = pendingInvitation();
        assertThat(invitation.statusAt(NOW.toInstant())).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.statusAt(NOW.plusDays(8).toInstant()))
                .isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    @DisplayName("expiry is exclusive: the instant it expires, it is expired")
    void expiry_is_exclusive() {
        Invitation invitation = pendingInvitation();
        assertThat(invitation.statusAt(NOW.plusDays(7).toInstant()))
                .isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    @DisplayName("acceptance records both the time and the user, together")
    void acceptance_records_both_facts() {
        Invitation invitation = pendingInvitation();
        UUID userId = UUID.randomUUID();
        invitation.accept(userId, NOW.plusDays(1));

        assertThat(invitation.acceptedUserId()).isEqualTo(userId);
        assertThat(invitation.acceptedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(invitation.statusAt(NOW.plusDays(1).toInstant()))
                .isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    @DisplayName("cannot be accepted twice")
    void cannot_be_accepted_twice() {
        Invitation invitation = pendingInvitation();
        invitation.accept(UUID.randomUUID(), NOW.plusDays(1));
        assertThatThrownBy(() -> invitation.accept(UUID.randomUUID(), NOW.plusDays(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been accepted");
    }

    @Test
    @DisplayName("cannot be accepted after expiry")
    void cannot_be_accepted_after_expiry() {
        Invitation invitation = pendingInvitation();
        assertThatThrownBy(() -> invitation.accept(UUID.randomUUID(), NOW.plusDays(8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("an accepted invitation stays accepted even once its expiry passes")
    void acceptance_outlives_expiry() {
        Invitation invitation = pendingInvitation();
        invitation.accept(UUID.randomUUID(), NOW.plusDays(1));
        assertThat(invitation.statusAt(NOW.plusDays(99).toInstant()))
                .isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a branch-scoped role must name a branch")
    void branch_scoped_role_requires_branch() {
        assertThatThrownBy(() -> Invitation.issue(TENANT, "a@b.com", Role.BRANCH_MANAGER, null,
                "hash", NOW.plusDays(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
