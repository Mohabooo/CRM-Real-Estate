package com.rescrm.integration;

import com.rescrm.commercialmodel.policy.DepositMeaning;
import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.inventory.domain.ProjectStatus;
import com.rescrm.inventory.domain.UnitStatus;
import com.rescrm.inventory.service.DeveloperService;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.Role;
import com.rescrm.reservations.domain.Reservation;
import com.rescrm.reservations.domain.ReservationStatus;
import com.rescrm.reservations.service.ReservationService;
import com.rescrm.support.TestIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Epic 4's stories end to end against a real PostgreSQL.
 *
 * <p>The thread running through all of them is that a reservation row and a unit's status
 * are two facts that must agree. Placing a hold does not withhold the unit; confirming does;
 * releasing gives it back. Every test here checks both sides, because a reservation that
 * says one thing while the unit says another is the bug this epic exists to prevent.
 */
@DisplayName("Reservation lifecycle")
class ReservationLifecycleIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private DeveloperService developerService;
    @Autowired private ProjectService projectService;
    @Autowired private UnitService unitService;
    @Autowired private LeadService leadService;
    @Autowired private ReservationService reservationService;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private UUID projectId;
    private UUID unitId;
    private UUID leadId;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Res " + unique, "own_inventory", "Main", "Owner",
                "res-" + unique + "@example.com", "a-long-enough-password");
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);

        projectId = projectService.create("own_inventory", null, null, "Res Project", null,
                null).id();
        projectService.changeStatus(projectId, ProjectStatus.ACTIVE, "launch");
        unitId = unitService.create(projectId, null, "R-101", "apartment", null, null, null,
                egp("2500000.00")).id();
        leadId = leadService.capture("Buyer", "+201000000001", null, "walk-in",
                tenant.initialBranch().id(), null, false).id();
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private static Money egp(String amount) {
        return Money.of(amount, CurrencyCode.EGP);
    }

    private Reservation place() {
        return reservationService.place(unitId, leadId, null, null, null, false);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .isEqualTo(expected));
    }

    // ========================================================================= E4-S1

    @Nested
    @DisplayName("E4-S1 placing and confirming")
    class Placing {

        @Test
        @DisplayName("a hold does not withhold the unit; confirming does")
        void the_unit_is_withheld_only_on_confirmation() {
            Reservation held = place();

            assertThat(held.status()).isEqualTo(ReservationStatus.PENDING);
            assertThat(unitService.get(unitId).status())
                    .as("E4-S1: the unit becomes reserved only on confirmation")
                    .isEqualTo(UnitStatus.AVAILABLE);

            reservationService.confirm(held.id());
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.RESERVED);
        }

        @Test
        @DisplayName("the expiry defaults from tenant settings when none is given")
        void expiry_defaults_from_settings() {
            Reservation held = place();

            // The provisioned tenant configures nothing, so the seven-day default applies.
            assertThat(held.expiresAt()).isAfter(held.reservedAt().plusDays(6));
            assertThat(held.expiresAt()).isBefore(held.reservedAt().plusDays(8));
        }

        @Test
        @DisplayName("a caller-supplied expiry is honoured")
        void an_explicit_expiry_wins() {
            OffsetDateTime wanted = OffsetDateTime.now().plusDays(2);
            Reservation held = reservationService.place(unitId, leadId, null, wanted, null,
                    false);
            assertThat(held.expiresAt()).isEqualTo(wanted);
        }

        @Test
        @DisplayName("releasing a confirmed hold gives the unit back")
        void release_returns_the_unit() {
            Reservation held = place();
            reservationService.confirm(held.id());

            reservationService.release(held.id(), "customer changed their mind");

            assertThat(reservationService.get(held.id()).status())
                    .isEqualTo(ReservationStatus.RELEASED);
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("cancelling a pending hold touches no unit, because none was withheld")
        void cancel_touches_no_unit() {
            Reservation held = place();
            reservationService.cancel(held.id(), "duplicate enquiry");

            assertThat(reservationService.get(held.id()).status())
                    .isEqualTo(ReservationStatus.CANCELLED);
            assertThat(unitService.get(unitId).status()).isEqualTo(UnitStatus.AVAILABLE);
        }

        @Test
        @DisplayName("every transition is audited, as doc 18 requires")
        void transitions_are_audited() {
            Reservation held = place();
            reservationService.confirm(held.id());
            reservationService.release(held.id(), "gone");

            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Reservation", held.id()))
                    .extracting(event -> event.action())
                    .contains(AuditAction.RESERVATION_PLACED.name(),
                            AuditAction.RESERVATION_CONFIRMED.name(),
                            AuditAction.RESERVATION_RELEASED.name());
        }
    }

    // ================================================== the gate Epic 3 left open

    @Nested
    @DisplayName("what cannot be reserved")
    class NotReservable {

        @Test
        @DisplayName("a unit in a project that is not selling yet")
        void a_draft_project_is_refused() {
            // Epic 3 left this deliberately: ProjectStatus.isSelling() existed with nothing
            // enforcing it, because the right gate is where somebody first tries to sell,
            // not on the unit. This is that gate.
            UUID draftProject = projectService.create("own_inventory", null, null, "Draft",
                    null, null).id();
            UUID draftUnit = unitService.create(draftProject, null, "D-1", null, null, null,
                    null, egp("1000000.00")).id();

            assertCode(() -> reservationService.place(draftUnit, leadId, null, null, null,
                    false), ErrorCode.BUSINESS_RULE_VIOLATION);
        }

        @Test
        @DisplayName("a blocked unit")
        void a_blocked_unit_is_refused() {
            unitService.block(unitId, "structural survey");
            assertCode(() -> place(), ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("a unit that is already sold")
        void a_sold_unit_is_refused() {
            assertThat(unitService.claimForSale(unitId).won()).isTrue();
            assertCode(() -> place(), ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("a unit somebody else already holds — 409 naming the holder")
        void a_held_unit_is_refused_with_the_holder() {
            Reservation first = place();

            assertThatThrownBy(() -> place())
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException api = (ApiException) thrown;
                        assertThat(api.code()).isEqualTo(ErrorCode.CONFLICT);
                        // Doc 23 F2/A3: the response names the current holder.
                        assertThat(api.details()).containsKey("heldByReservationId");
                        assertThat(api.details().get("heldByReservationId"))
                                .isEqualTo(first.id().toString());
                    });
        }

        @Test
        @DisplayName("but the unit frees up once the hold ends")
        void a_released_unit_can_be_held_again() {
            Reservation first = place();
            reservationService.cancel(first.id(), "changed their mind");

            assertThat(place().status()).isEqualTo(ReservationStatus.PENDING);
        }
    }

    // ========================================================================= E4-S3

    @Nested
    @DisplayName("E4-S3 extension")
    class Extension {

        @Test
        @DisplayName("a branch manager may extend within the tenant's limit")
        void within_the_limit() {
            Reservation held = place();
            reservationService.confirm(held.id());

            TestIdentity.signOut();
            TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(),
                    Role.BRANCH_MANAGER, null);

            Reservation extended = reservationService.extend(held.id(),
                    held.expiresAt().plusDays(5), "customer is arranging finance");

            assertThat(extended.extensionCount()).isEqualTo(1);
            assertThat(extended.expiresAt()).isEqualTo(held.expiresAt().plusDays(5));
        }

        @Test
        @DisplayName("beyond the limit a branch manager is refused; an owner is not")
        void beyond_the_limit_needs_an_owner() {
            Reservation held = place();
            reservationService.confirm(held.id());
            OffsetDateTime wayOut = held.originalExpiresAt().plusDays(30);

            TestIdentity.signOut();
            TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(),
                    Role.BRANCH_MANAGER, null);
            assertCode(() -> reservationService.extend(held.id(), wayOut, "big customer"),
                    ErrorCode.FORBIDDEN);

            TestIdentity.signOut();
            TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
            assertThat(reservationService.extend(held.id(), wayOut, "big customer")
                    .expiresAt()).isEqualTo(wayOut);
        }

        @Test
        @DisplayName("the limit is measured from where the hold started, not from today")
        void the_limit_is_cumulative() {
            Reservation held = place();
            reservationService.confirm(held.id());
            OffsetDateTime original = held.originalExpiresAt();

            TestIdentity.signOut();
            TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(),
                    Role.BRANCH_MANAGER, null);

            reservationService.extend(held.id(), original.plusDays(4), "first");

            // Four days used, limit is seven. A fifth day more is within it; a further six
            // would take the total to ten and is not — measured from the original expiry,
            // so a manager cannot walk the hold forward a few days at a time forever.
            reservationService.extend(held.id(), original.plusDays(6), "second");
            assertCode(() -> reservationService.extend(held.id(), original.plusDays(12),
                    "third"), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("an extension cannot move the expiry backwards")
        void extensions_go_forward_only() {
            Reservation held = place();
            reservationService.confirm(held.id());

            assertCode(() -> reservationService.extend(held.id(),
                    held.expiresAt().minusDays(1), "shorten"), ErrorCode.VALIDATION_FAILED);
        }
    }

    // ========================================================================= E4-S4

    @Nested
    @DisplayName("E4-S4 the deposit and what it means")
    class Deposit {

        @Test
        @DisplayName("own-inventory: the deposit is the tenant's own cash")
        void own_inventory_deposit_is_tenant_cash() {
            Reservation held = reservationService.place(unitId, leadId, null, null,
                    egp("50000.00"), true);

            assertThat(held.depositAmount().toPlainString()).isEqualTo("50000.00");
            assertThat(held.isDepositReceived()).isTrue();
            assertThat(reservationService.depositMeaningFor(held.id()))
                    .isEqualTo(DepositMeaning.TENANT_CASH);
        }

        @Test
        @DisplayName("brokered: the same amount is a confirmation the developer received it")
        void brokered_deposit_is_a_confirmation() {
            UUID developer = developerService.register("Partner Developer", null, null).id();
            UUID brokered = projectService.create("brokered_inventory", developer, null,
                    "Theirs", null, null).id();
            projectService.changeStatus(brokered, ProjectStatus.ACTIVE, "launch");
            UUID brokeredUnit = unitService.create(brokered, null, "B-1", null, null, null,
                    null, egp("3000000.00")).id();

            Reservation held = reservationService.place(brokeredUnit, leadId, null, null,
                    egp("50000.00"), true);

            // Same number, different fact. Doc 25 section 4: inventing a Payment row from
            // this would put money in the tenant's accounts that never arrived.
            assertThat(held.depositAmount().toPlainString()).isEqualTo("50000.00");
            assertThat(reservationService.depositMeaningFor(held.id()))
                    .isEqualTo(DepositMeaning.DEVELOPER_RECEIPT_CONFIRMATION);
        }

        @Test
        @DisplayName("a deposit can be recorded after the hold was placed")
        void a_deposit_can_arrive_later() {
            Reservation held = place();
            assertThat(held.depositAmount()).isNull();

            Reservation withDeposit = reservationService.recordDeposit(held.id(),
                    egp("25000.00"), true);

            assertThat(withDeposit.depositAmount().toPlainString()).isEqualTo("25000.00");
            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Reservation", held.id()))
                    .extracting(event -> event.action())
                    .contains(AuditAction.RESERVATION_DEPOSIT_RECORDED.name());
        }

        @Test
        @DisplayName("a zero deposit is refused")
        void a_deposit_is_positive() {
            assertCode(() -> reservationService.place(unitId, leadId, null, null,
                    Money.zero(CurrencyCode.EGP), true), ErrorCode.VALIDATION_FAILED);
        }
    }
}
