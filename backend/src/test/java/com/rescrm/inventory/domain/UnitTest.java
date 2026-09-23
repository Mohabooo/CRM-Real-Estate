package com.rescrm.inventory.domain;

import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unit's own rules (doc 16 section 9, doc 18 section 2).
 *
 * <p>The transitions are asserted here with no database, and again against a real PostgreSQL
 * in {@code InventoryLifecycleIT} and {@code UnitConcurrencyIT}. Both matter: this is where
 * the vocabulary is enforced, and those are where the guarantee under concurrency is.
 */
@DisplayName("Unit")
class UnitTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    private static Money egp(String amount) {
        return Money.of(amount, CurrencyCode.EGP);
    }

    private static Unit newUnit() {
        return Unit.create(TENANT, PROJECT, null, " A-101 ", "apartment",
                new BigDecimal("142.50"), "3", "garden", egp("2500000.00"));
    }

    @Nested
    @DisplayName("on creation")
    class OnCreation {

        @Test
        @DisplayName("enters inventory available, with its code trimmed")
        void enters_available() {
            Unit unit = newUnit();

            assertThat(unit.status()).isEqualTo(UnitStatus.AVAILABLE);
            assertThat(unit.code()).isEqualTo("A-101");
            assertThat(unit.blockedReason()).isNull();
        }

        @Test
        @DisplayName("keeps the list price as Money, at two decimal places")
        void price_is_money() {
            assertThat(newUnit().listPrice().toPlainString()).isEqualTo("2500000.00");
            assertThat(newUnit().listPrice().currency()).isEqualTo(CurrencyCode.EGP);
        }

        @Test
        @DisplayName("refuses a missing, zero or negative list price")
        void price_must_be_positive() {
            assertThatThrownBy(() -> Unit.create(TENANT, PROJECT, null, "A-102", null, null,
                    null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("list price");

            assertThatThrownBy(() -> Unit.create(TENANT, PROJECT, null, "A-102", null, null,
                    null, null, Money.zero(CurrencyCode.EGP)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than zero");
        }

        @Test
        @DisplayName("refuses a blank code")
        void code_is_required() {
            assertThatThrownBy(() -> Unit.create(TENANT, PROJECT, null, "  ", null, null, null,
                    null, egp("100.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a non-positive area, but allows none at all")
        void area_is_optional_but_positive() {
            assertThat(Unit.create(TENANT, PROJECT, null, "A-103", null, null, null, null,
                    egp("100.00")).areaSqm()).isNull();

            assertThatThrownBy(() -> Unit.create(TENANT, PROJECT, null, "A-104", null,
                    new BigDecimal("-1"), null, null, egp("100.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the status machine")
    class StatusMachine {

        @Test
        @DisplayName("available -> reserved -> sold")
        void the_documented_path() {
            Unit unit = newUnit();

            unit.markReserved();
            assertThat(unit.status()).isEqualTo(UnitStatus.RESERVED);

            unit.markSold();
            assertThat(unit.status()).isEqualTo(UnitStatus.SOLD);
        }

        @Test
        @DisplayName("available -> sold directly, because a deal need not pass through reserved")
        void available_goes_straight_to_sold() {
            Unit unit = newUnit();
            unit.markSold();
            assertThat(unit.status()).isEqualTo(UnitStatus.SOLD);
        }

        @Test
        @DisplayName("a sold unit cannot be reserved or sold again")
        void sold_is_terminal_until_cancellation() {
            Unit unit = newUnit();
            unit.markSold();

            assertThatThrownBy(unit::markReserved).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(unit::markSold).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a sold unit returns to inventory on cancellation")
        void cancellation_returns_it() {
            Unit unit = newUnit();
            unit.markSold();

            unit.returnToInventory();
            assertThat(unit.status()).isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("blocking requires a reason and keeps it")
        void blocking_requires_a_reason() {
            Unit unit = newUnit();

            assertThatThrownBy(() -> unit.block("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(unit.status())
                    .as("a rejected block must leave the unit as it was")
                    .isEqualTo(UnitStatus.AVAILABLE);

            unit.block("structural survey");
            assertThat(unit.status()).isEqualTo(UnitStatus.BLOCKED);
            assertThat(unit.blockedReason()).isEqualTo("structural survey");
        }

        @Test
        @DisplayName("unblocking clears the reason")
        void unblocking_clears_the_reason() {
            Unit unit = newUnit();
            unit.block("structural survey");

            unit.unblock();
            assertThat(unit.status()).isEqualTo(UnitStatus.AVAILABLE);
            assertThat(unit.blockedReason())
                    .as("a reason outliving its block explains a state that has ended")
                    .isNull();
        }

        @Test
        @DisplayName("a sold unit cannot be blocked")
        void sold_cannot_be_blocked() {
            Unit unit = newUnit();
            unit.markSold();

            assertThatThrownBy(() -> unit.block("too late"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("only a blocked unit can be unblocked")
        void only_blocked_unblocks() {
            assertThatThrownBy(newUnit()::unblock).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("attributes")
    class Attributes {

        @Test
        @DisplayName("there is no way to set the status through an attribute update")
        void attributes_cannot_move_the_status() {
            Unit unit = newUnit();
            unit.block("survey");

            // The signature is the guarantee: updateAttributes takes no status, so doc 23's
            // "PATCH rejects any attempt to write status" is not a runtime check that could
            // be forgotten — there is nothing to send.
            unit.updateAttributes("villa", new BigDecimal("300"), "1", "sea", egp("9000000.00"),
                    null);

            assertThat(unit.status()).isEqualTo(UnitStatus.BLOCKED);
            assertThat(unit.listPrice().toPlainString()).isEqualTo("9000000.00");
            assertThat(unit.type()).isEqualTo("villa");
        }

        @Test
        @DisplayName("a rejected update leaves every field as it was")
        void rejected_update_changes_nothing() {
            Unit unit = newUnit();

            assertThatThrownBy(() -> unit.updateAttributes(null, new BigDecimal("-5"), null,
                    null, egp("3000000.00"), null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(unit.areaSqm()).isEqualByComparingTo("142.50");
            assertThat(unit.listPrice().toPlainString())
                    .as("the price must not survive a rejected update")
                    .isEqualTo("2500000.00");
        }

        @Test
        @DisplayName("a null argument leaves that field alone")
        void nulls_leave_fields_alone() {
            Unit unit = newUnit();
            unit.updateAttributes(null, null, null, null, egp("2600000.00"), null);

            assertThat(unit.type()).isEqualTo("apartment");
            assertThat(unit.listPrice().toPlainString()).isEqualTo("2600000.00");
        }
    }

    @Nested
    @DisplayName("what the browse default shows")
    class BrowseDefault {

        @Test
        @DisplayName("available and reserved are in; sold and blocked are out")
        void default_view_excludes_sold_and_blocked() {
            // E3-S4: "Sold and blocked units excluded from the default view."
            assertThat(UnitStatus.AVAILABLE.isInDefaultBrowse()).isTrue();
            assertThat(UnitStatus.RESERVED.isInDefaultBrowse()).isTrue();
            assertThat(UnitStatus.SOLD.isInDefaultBrowse()).isFalse();
            assertThat(UnitStatus.BLOCKED.isInDefaultBrowse()).isFalse();
        }

        @Test
        @DisplayName("a reserved unit is still sellable; a blocked one is not")
        void sellability() {
            assertThat(UnitStatus.RESERVED.isSellable()).isTrue();
            assertThat(UnitStatus.BLOCKED.isSellable()).isFalse();
            assertThat(UnitStatus.SOLD.isSellable()).isFalse();
        }
    }
}
