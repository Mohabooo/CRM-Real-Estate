package com.rescrm.platform.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantContext")
class TenantContextTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("holds and returns the current tenant")
    void holds_current_tenant() {
        assertThat(TenantContext.isSet()).isFalse();

        TenantContext.set(TENANT_A);

        assertThat(TenantContext.isSet()).isTrue();
        assertThat(TenantContext.current()).contains(TENANT_A);
        assertThat(TenantContext.require()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("refuses tenant-scoped access when no tenant is established")
    void requires_a_tenant() {
        // Failing loudly is the safe behaviour: a query that runs with no tenant would
        // otherwise be one missing predicate away from reading across tenants.
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(TenantContext.TenantContextMissingException.class)
                .hasMessageContaining("refused");
    }

    @Test
    @DisplayName("clear removes the value so a pooled thread cannot leak it")
    void clear_removes_the_value() {
        TenantContext.set(TENANT_A);
        TenantContext.clear();

        assertThat(TenantContext.isSet()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("callAs restores the previous tenant afterwards")
    void call_as_restores_previous() {
        TenantContext.set(TENANT_A);

        UUID observed = TenantContext.callAs(TENANT_B, TenantContext::require);

        assertThat(observed).isEqualTo(TENANT_B);
        assertThat(TenantContext.require()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("callAs clears when there was no previous tenant")
    void call_as_clears_when_unset() {
        TenantContext.callAs(TENANT_A, () -> null);

        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("the value does not cross threads")
    void does_not_leak_across_threads() throws Exception {
        TenantContext.set(TENANT_A);

        Boolean setOnOtherThread = CompletableFuture
                .supplyAsync(TenantContext::isSet)
                .get();

        assertThat(setOnOtherThread).isFalse();
    }

    @Test
    @DisplayName("rejects a null tenant")
    void rejects_null() {
        assertThatThrownBy(() -> TenantContext.set(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
