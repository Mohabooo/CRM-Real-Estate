package com.rescrm.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tenant")
class TenantTest {

    @Test
    @DisplayName("is provisioned into 'provisioning', never straight to active")
    void starts_provisioning() {
        Tenant tenant = Tenant.provision("Acme Realty", "own_inventory", null);
        assertThat(tenant.status()).isEqualTo(TenantStatus.PROVISIONING);
        assertThat(tenant.id()).isNotNull();
        assertThat(tenant.settings()).isEqualTo("{}");
    }

    @Test
    @DisplayName("rejects an unknown commercial model rather than storing it")
    void rejects_unknown_model() {
        assertThatThrownBy(() -> Tenant.provision("Acme", "rent_to_own", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rent_to_own");
    }

    @Test
    @DisplayName("rejects a blank name")
    void rejects_blank_name() {
        assertThatThrownBy(() -> Tenant.provision("   ", "own_inventory", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses an undocumented transition")
    void refuses_undocumented_transition() {
        Tenant tenant = Tenant.provision("Acme", "own_inventory", null);
        assertThatThrownBy(() -> tenant.transitionTo(TenantStatus.CLOSED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot move from");
    }

    @Test
    @DisplayName("walks the documented chain")
    void walks_the_chain() {
        Tenant tenant = Tenant.provision("Acme", "brokered_inventory", null);
        tenant.transitionTo(TenantStatus.ACTIVE);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        tenant.transitionTo(TenantStatus.SUSPENDED);
        tenant.transitionTo(TenantStatus.CLOSED);
        assertThat(tenant.status()).isEqualTo(TenantStatus.CLOSED);
    }
}
