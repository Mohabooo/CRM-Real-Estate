package com.rescrm.commercialmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commercial model's behavioural predicates (doc 25, section 4).
 *
 * <p>These encode the switch table: which model requires a developer, which records customer
 * payments, which earns external commission. Later epics resolve these through the eight
 * policy interfaces rather than by calling these methods directly.
 */
@DisplayName("CommercialModel")
class CommercialModelTest {

    @Test
    @DisplayName("a developer is required only for brokered inventory (constraint C10)")
    void developer_requirement() {
        assertThat(CommercialModel.BROKERED_INVENTORY.requiresDeveloper()).isTrue();
        assertThat(CommercialModel.OWN_INVENTORY.requiresDeveloper()).isFalse();
    }

    @Test
    @DisplayName("only own inventory records customer payments (rule R-PAY-0)")
    void collection_responsibility() {
        assertThat(CommercialModel.OWN_INVENTORY.tenantCollectsCustomerPayments()).isTrue();
        // Brokered customer collection is out of MVP by decision: the developer collects, and
        // a half-populated ledger is worse than none (doc 25, section 4).
        assertThat(CommercialModel.BROKERED_INVENTORY.tenantCollectsCustomerPayments()).isFalse();
    }

    @Test
    @DisplayName("only brokered inventory has inbound commission (rule R-COMM-5)")
    void inbound_commission() {
        assertThat(CommercialModel.BROKERED_INVENTORY.hasInboundCommission()).isTrue();
        assertThat(CommercialModel.OWN_INVENTORY.hasInboundCommission()).isFalse();
    }

    @Test
    @DisplayName("exactly two models exist")
    void two_models() {
        // Doc 25 section 2 collapses three business scenarios into two models deliberately.
        // A third constant appearing here means that decision was revisited without the
        // documentation being updated.
        assertThat(CommercialModel.values()).hasSize(2);
    }
}
