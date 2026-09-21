package com.rescrm.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tenant lifecycle")
class TenantStatusTest {

    @Test
    @DisplayName("follows the documented chain and nothing else")
    void documented_chain_only() {
        assertThat(TenantStatus.PROVISIONING.canTransitionTo(TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.SUSPENDED)).isTrue();
        assertThat(TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.CLOSED)).isTrue();

        // Undescribed by doc 16 and therefore deliberately absent. If the business decides
        // these should exist, that is a decision plus a test change, not a silent addition.
        assertThat(TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.ACTIVE)).isFalse();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.CLOSED)).isFalse();
        assertThat(TenantStatus.PROVISIONING.canTransitionTo(TenantStatus.SUSPENDED)).isFalse();
    }

    @Test
    @DisplayName("closed is terminal")
    void closed_is_terminal() {
        for (TenantStatus next : TenantStatus.values()) {
            assertThat(TenantStatus.CLOSED.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    @DisplayName("only an active tenant permits access")
    void only_active_permits_access() {
        assertThat(TenantStatus.ACTIVE.permitsAccess()).isTrue();
        assertThat(TenantStatus.PROVISIONING.permitsAccess()).isFalse();
        assertThat(TenantStatus.SUSPENDED.permitsAccess()).isFalse();
        assertThat(TenantStatus.CLOSED.permitsAccess()).isFalse();
    }

    @Test
    @DisplayName("codes round-trip through the stored spelling")
    void codes_round_trip() {
        for (TenantStatus status : TenantStatus.values()) {
            assertThat(TenantStatus.fromCode(status.code())).isEqualTo(status);
        }
    }
}
