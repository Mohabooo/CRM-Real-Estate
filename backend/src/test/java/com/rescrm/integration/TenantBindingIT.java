package com.rescrm.integration;

import com.rescrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When does a connection learn which tenant it is for?
 *
 * <p>This is the test that would have caught three production bugs at once, and it exists
 * because reasoning about it was not enough.
 *
 * <p>{@code TenantAwareDataSource} stamps {@code app.current_tenant_id} onto a connection at
 * the moment the connection is taken. Spring's {@code JpaTransactionManager} takes one when
 * the transaction begins. So a {@code @Transactional} method that establishes the tenant
 * inside its own body is stamped with the empty binding — which every row-level security
 * policy treats as matching nothing — and every insert it makes is refused.
 *
 * <p>{@code TenantProvisioningService.provision} and {@code InvitationService.accept} both did
 * exactly that, and neither test suite noticed, because the integration tests connect as a
 * superuser and a superuser bypasses row-level security entirely. Nothing failed; the writes
 * simply would not have worked anywhere else.
 *
 * <p>So this asserts the ordering itself rather than a symptom of it, and does so without
 * needing an unprivileged role: the binding the database reports is the same either way.
 */
@ContextConfiguration(classes = TenantBindingIT.Probe.class)
@DisplayName("Tenant binding on the database connection")
class TenantBindingIT extends AbstractPostgresIT {

    /** Reports the binding the database sees from inside a transaction. */
    @TestConfiguration
    static class Probe {

        @Bean
        BindingProbe bindingProbe(JdbcTemplate jdbc) {
            return new BindingProbe(jdbc);
        }
    }

    static class BindingProbe {

        private final JdbcTemplate jdbc;

        BindingProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** What a write inside this transaction would be checked against. */
        @Transactional
        public String bindingInsideTransaction() {
            return jdbc.queryForObject(
                    "SELECT current_setting('app.current_tenant_id', true)", String.class);
        }

        /** Establishes the tenant in the body — the order that does not work. */
        @Transactional
        public String establishTenantInsideTransaction(UUID tenantId) {
            return TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                    "SELECT current_setting('app.current_tenant_id', true)", String.class));
        }
    }

    @Autowired
    private BindingProbe probe;

    @Test
    @DisplayName("is correct when the tenant is established before the transaction begins")
    void establishing_the_tenant_first_binds_the_connection() {
        UUID tenantId = UUID.randomUUID();

        String binding = TenantContext.callAs(tenantId, probe::bindingInsideTransaction);

        assertThat(binding)
                .as("a write in this transaction would be checked against the right tenant")
                .isEqualTo(tenantId.toString());
    }

    @Test
    @DisplayName("is EMPTY when the tenant is established inside the transaction instead")
    void establishing_the_tenant_inside_the_transaction_is_too_late() {
        UUID tenantId = UUID.randomUUID();

        String binding = probe.establishTenantInsideTransaction(tenantId);

        assertThat(binding)
                .as("""
                        The connection was already stamped when the transaction began. Every \
                        policy treats an empty binding as matching nothing, so inserts here \
                        are refused in production and pass only because these tests connect \
                        as a superuser. If this assertion ever fails because the binding is \
                        now correct, Spring's connection acquisition has changed — and the \
                        nested-bean structure in TenantProvisioningService, InvitationService \
                        and AuthenticationService can be revisited.""")
                .isEmpty();
    }

    @Test
    @DisplayName("is empty when no tenant is established at all")
    void no_tenant_means_no_binding() {
        TenantContext.clear();

        assertThat(probe.bindingInsideTransaction()).isEmpty();
    }
}
