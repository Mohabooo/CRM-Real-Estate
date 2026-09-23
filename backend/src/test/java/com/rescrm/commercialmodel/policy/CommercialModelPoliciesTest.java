package com.rescrm.commercialmodel.policy;

import com.rescrm.commercialmodel.CommercialModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first policy point, and the resolution mechanism behind all eight (doc 25, section 6).
 *
 * <p>Doc 21's design constraint 5 asks for every policy to be independently testable with a
 * fixture per model. That is what keeps the two-model matrix manageable as the other seven
 * arrive: each one is a handful of assertions here rather than a combinatorial explosion in
 * the integration suite.
 */
@DisplayName("Commercial-model policies")
class CommercialModelPoliciesTest {

    private final CommercialModelPolicies policies = new CommercialModelPolicies();

    private static String codeOf(CommercialModel model) {
        return model.name().toLowerCase(Locale.ROOT);
    }

    @Nested
    @DisplayName("own_inventory")
    class OwnInventory {

        private final InventoryOwnershipPolicy policy =
                policies.inventoryOwnership("own_inventory");

        @Test
        @DisplayName("the tenant sells its own stock and there is no developer")
        void tenant_sells_its_own_stock() {
            assertThat(policy.requiresDeveloper()).isFalse();
            assertThat(policy.sellerOfRecord()).isEqualTo(SellerOfRecord.TENANT);
        }

        @Test
        @DisplayName("a developer on an owned project is rejected, not merely unnecessary")
        void a_developer_is_refused() {
            // A stray developer_id on an owned project would make it look brokered to every
            // commission query afterwards, which is why this is a rejection and not a shrug.
            assertThat(policy.rejectDeveloperAssignment(true))
                    .hasValueSatisfying(reason ->
                            assertThat(reason).contains("cannot name a developer"));
            assertThat(policy.rejectDeveloperAssignment(false)).isEmpty();
        }
    }

    @Nested
    @DisplayName("brokered_inventory")
    class BrokeredInventory {

        private final InventoryOwnershipPolicy policy =
                policies.inventoryOwnership("brokered_inventory");

        @Test
        @DisplayName("an external developer owns the stock")
        void developer_owns_the_stock() {
            assertThat(policy.requiresDeveloper()).isTrue();
            assertThat(policy.sellerOfRecord()).isEqualTo(SellerOfRecord.DEVELOPER);
        }

        @Test
        @DisplayName("a brokered project without a developer is rejected")
        void a_developer_is_required() {
            assertThat(policy.rejectDeveloperAssignment(false))
                    .hasValueSatisfying(reason ->
                            assertThat(reason).contains("must be named"));
            assertThat(policy.rejectDeveloperAssignment(true)).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("every commercial model resolves to a policy")
        void every_model_resolves() {
            // The point of asserting over values() rather than the two we know about: a third
            // model added later fails here immediately instead of silently defaulting to one
            // of the existing two somewhere downstream.
            for (CommercialModel model : CommercialModel.values()) {
                assertThat(policies.inventoryOwnership(codeOf(model)))
                        .as("no InventoryOwnershipPolicy for %s", model)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("an unknown code is refused, naming what is accepted")
        void unknown_code_is_refused() {
            assertThatThrownBy(() -> policies.inventoryOwnership("rent_to_own"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("own_inventory");
        }

        @Test
        @DisplayName("a null or blank code is refused rather than defaulted")
        void blank_code_is_refused() {
            assertThatThrownBy(() -> policies.inventoryOwnership(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> policies.inventoryOwnership(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the two models do not resolve to the same policy")
        void the_models_differ() {
            assertThat(policies.inventoryOwnership("own_inventory").requiresDeveloper())
                    .isNotEqualTo(
                            policies.inventoryOwnership("brokered_inventory").requiresDeveloper());
        }
    }
}
