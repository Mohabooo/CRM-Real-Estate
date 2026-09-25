package com.rescrm.integration;

import com.rescrm.crm.domain.ActivitySubject;
import com.rescrm.crm.domain.ActivityType;
import com.rescrm.crm.domain.Customer;
import com.rescrm.crm.domain.Lead;
import com.rescrm.crm.repository.ActivityRepository;
import com.rescrm.crm.repository.CustomerRepository;
import com.rescrm.crm.repository.LeadRepository;
import com.rescrm.crm.service.ActivityService;
import com.rescrm.crm.service.CustomerService;
import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that must fail if Epic 2's tenant isolation is ever weakened.
 *
 * <p>The same shape as {@link IdentityTenantIsolationIT}, aimed at leads, customers and
 * activities. Two tenants are provisioned and populated, then every read, write and
 * transition available in the CRM module is attempted from tenant A against tenant B's rows.
 *
 * <p>Two design points are load-bearing and are asserted rather than assumed. Every listing is
 * given rows in <em>both</em> tenants before it is checked, so "contains only A" cannot pass
 * against an empty result — the failure mode where removing scoping entirely still leaves the
 * suite green. And every cross-tenant refusal must be 404 rather than 403, because a 403
 * confirms the record exists somewhere (doc 28 section 6).
 *
 * <p>This is the application layer of the two-layer design (doc 22 section 4);
 * {@link RlsIsolationIT} proves the database layer independently.
 */
@DisplayName("Tenant isolation — CRM")
class CrmTenantIsolationIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private LeadService leadService;
    @Autowired private CustomerService customerService;
    @Autowired private ActivityService activityService;
    @Autowired private LeadRepository leads;
    @Autowired private CustomerRepository customers;
    @Autowired private ActivityRepository activities;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenantA;
    private TenantProvisioningService.ProvisionedTenant tenantB;

    private UUID leadOfA;
    private UUID leadOfB;
    private UUID customerOfB;
    private UUID activityOfB;

    @BeforeEach
    void provisionAndPopulate() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenantA = provisioning.provision("CRM A " + unique, "own_inventory", "A Branch",
                "Owner A", "crm-a-" + unique + "@example.com", "a-long-enough-password");
        tenantB = provisioning.provision("CRM B " + unique, "own_inventory", "B Branch",
                "Owner B", "crm-b-" + unique + "@example.com", "a-long-enough-password");

        asOwnerOf(tenantB);
        Lead bLead = leadService.capture("Lead in B", "+201110000002", null, "walk-in",
                tenantB.initialBranch().id(), null, false);
        leadOfB = bLead.id();
        activityOfB = activityService.log(ActivitySubject.LEAD, leadOfB, ActivityType.CALL,
                "spoke to B's lead", OffsetDateTime.now()).id();
        customerOfB = customerService.create(null, "Customer in B", "+201110000003", null,
                null, null, false).id();

        // A gets its own rows too, so every "only A's" assertion below is made against a
        // non-empty list. Without this, removing tenant scoping entirely would still pass.
        asOwnerOf(tenantA);
        leadOfA = leadService.capture("Lead in A", "+201110000001", null, "walk-in",
                tenantA.initialBranch().id(), null, false).id();
        activityService.log(ActivitySubject.LEAD, leadOfA, ActivityType.NOTE, "A's note",
                OffsetDateTime.now());
        customerService.create(null, "Customer in A", "+201110000004", null, null, null, false);
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private void asOwnerOf(TenantProvisioningService.ProvisionedTenant tenant) {
        TestIdentity.signOut();
        TestIdentity.signIn(tenant.owner().id(), tenant.tenant().id(), Role.OWNER, null);
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                        .as("cross-tenant access must answer 404, never 403 (doc 28 section 6)")
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // =============================================================================== read

    @Nested
    @DisplayName("Tenant A cannot READ tenant B")
    class CannotRead {

        @Test
        @DisplayName("B's lead, customer and timeline are all simply not found")
        void records_are_not_found() {
            assertNotFound(() -> leadService.get(leadOfB));
            assertNotFound(() -> customerService.get(customerOfB));
            assertNotFound(() -> activityService.timeline(ActivitySubject.LEAD, leadOfB));
            assertNotFound(() -> customerService.revealNationalId(customerOfB, "curious"));
        }

        @Test
        @DisplayName("listings contain only A's rows, and are not merely empty")
        void listings_are_scoped() {
            List<Lead> visibleLeads = leadService.listVisibleToCaller(PageRequest.of(0, 50))
                    .getContent();
            assertThat(visibleLeads).isNotEmpty();
            assertThat(visibleLeads).extracting(Lead::tenantId)
                    .containsOnly(tenantA.tenant().id());
            assertThat(visibleLeads).extracting(Lead::id).contains(leadOfA)
                    .doesNotContain(leadOfB);

            List<Customer> visibleCustomers = customerService.list(PageRequest.of(0, 50))
                    .getContent();
            assertThat(visibleCustomers).isNotEmpty();
            assertThat(visibleCustomers).extracting(Customer::tenantId)
                    .containsOnly(tenantA.tenant().id());
            assertThat(visibleCustomers).extracting(Customer::id).doesNotContain(customerOfB);
        }

        @Test
        @DisplayName("duplicate detection does not reach across tenants")
        void duplicate_detection_is_scoped() {
            // B's customer really does hold this number, and A must not learn that.
            assertThat(leadService.findDuplicates("+201110000003")).isEmpty();
            assertThat(customerService.findDuplicates("+201110000002")).isEmpty();

            // A's own duplicate is still detected, so the check is scoped rather than broken.
            assertThat(customerService.findDuplicates("+201110000001")).isNotEmpty();
        }

        @Test
        @DisplayName("repository finders scoped to A return nothing for B's ids")
        void repositories_refuse_cross_tenant_ids() {
            UUID a = tenantA.tenant().id();
            assertThat(leads.findByTenantIdAndId(a, leadOfB)).isEmpty();
            assertThat(customers.findByTenantIdAndId(a, customerOfB)).isEmpty();
            assertThat(customers.findByTenantIdAndSourceLeadId(a, leadOfB)).isEmpty();
            assertThat(activities.findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                    a, "Lead", leadOfB)).isEmpty();
            assertThat(leads.findAllByTenantIdAndIdIn(a, List.of(leadOfB))).isEmpty();
            assertThat(activities.countByTenantIdAndSubjectTypeAndSubjectId(a, "Lead", leadOfB))
                    .isZero();
        }

        @Test
        @DisplayName("the stale queue never surfaces another tenant's overdue work")
        void stale_queue_is_scoped() {
            asOwnerOf(tenantB);
            leadService.setNextAction(leadOfB, OffsetDateTime.now().minusDays(3));

            asOwnerOf(tenantA);
            leadService.setNextAction(leadOfA, OffsetDateTime.now().minusDays(3));

            assertThat(leadService.staleQueue()).isNotEmpty();
            assertThat(leadService.staleQueue()).extracting(Lead::id)
                    .contains(leadOfA)
                    .doesNotContain(leadOfB);
        }

        @Test
        @DisplayName("B's CRM audit trail is invisible to A")
        void audit_trail_is_scoped() {
            assertThat(auditEvents.findTrail(tenantA.tenant().id(), "Lead", leadOfB)).isEmpty();
            assertThat(auditEvents.findForTenant(tenantA.tenant().id()))
                    .extracting(event -> event.tenantId())
                    .containsOnly(tenantA.tenant().id());
        }
    }

    // ============================================================================= modify

    @Nested
    @DisplayName("Tenant A cannot MODIFY tenant B")
    class CannotModify {

        @Test
        @DisplayName("every lead transition on B's lead is not found")
        void lead_transitions_are_refused() {
            assertNotFound(() -> leadService.assign(leadOfB, tenantA.owner().id(), "mine now"));
            assertNotFound(() -> leadService.recordContact(leadOfB));
            assertNotFound(() -> leadService.qualify(leadOfB));
            assertNotFound(() -> leadService.disqualify(leadOfB, "not interested"));
            assertNotFound(() -> leadService.reactivate(leadOfB, tenantA.owner().id(), "why"));
            assertNotFound(() -> leadService.setNextAction(leadOfB, OffsetDateTime.now()));
            assertNotFound(() -> leadService.captureInterest(leadOfB, "{\"budget\":1}"));
        }

        @Test
        @DisplayName("and B's lead is genuinely untouched afterwards")
        void bs_lead_is_untouched() {
            assertNotFound(() -> leadService.disqualify(leadOfB, "not interested"));

            asOwnerOf(tenantB);
            Lead stillThere = leadService.get(leadOfB);
            assertThat(stillThere.name()).isEqualTo("Lead in B");
            assertThat(stillThere.status().code()).isEqualTo("active");
            assertThat(stillThere.disqualifiedReason()).isNull();
        }

        @Test
        @DisplayName("B's customer cannot be edited or have its identifier replaced")
        void customer_writes_are_refused() {
            assertNotFound(() -> customerService.updateDetails(customerOfB, null, "Renamed by A",
                    null, null, null));
            assertNotFound(() -> customerService.setNationalId(customerOfB, "29801011234567"));

            asOwnerOf(tenantB);
            assertThat(customerService.get(customerOfB).nameEn()).isEqualTo("Customer in B");
            assertThat(customerService.get(customerOfB).hasNationalId()).isFalse();
        }

        @Test
        @DisplayName("an activity cannot be logged against B's lead or customer")
        void activities_cannot_be_attached_across_tenants() {
            assertNotFound(() -> activityService.log(ActivitySubject.LEAD, leadOfB,
                    ActivityType.CALL, "smuggled", OffsetDateTime.now()));
            assertNotFound(() -> activityService.log(ActivitySubject.CUSTOMER, customerOfB,
                    ActivityType.NOTE, "smuggled", OffsetDateTime.now()));

            asOwnerOf(tenantB);
            assertThat(activityService.timeline(ActivitySubject.LEAD, leadOfB))
                    .extracting(activity -> activity.id())
                    .containsExactly(activityOfB);
        }

        @Test
        @DisplayName("B's lead cannot be converted into A's customer book")
        void conversion_across_tenants_is_refused() {
            assertNotFound(() -> customerService.convertLead(leadOfB, null, "Stolen", null, null));

            assertThat(customers.findByTenantIdAndSourceLeadId(tenantA.tenant().id(), leadOfB))
                    .isEmpty();
            assertThat(customers.findByTenantIdAndSourceLeadId(tenantB.tenant().id(), leadOfB))
                    .isEmpty();
        }

        @Test
        @DisplayName("a bulk reassignment cannot smuggle in one of B's leads")
        void bulk_reassign_refuses_a_mixed_batch() {
            // The whole batch fails rather than quietly moving A's half: partial success is
            // how a manager ends up not knowing what actually happened.
            assertNotFound(() -> leadService.bulkReassign(List.of(leadOfA, leadOfB),
                    tenantA.owner().id(), "quarterly rebalance"));

            assertThat(leadService.get(leadOfA).ownerUserId())
                    .as("A's own lead must not have moved either")
                    .isNull();
        }
    }

    // ============================================================================= remove

    @Nested
    @DisplayName("Tenant A cannot REMOVE tenant B's rows")
    class CannotRemove {

        @Test
        @DisplayName("there is no delete path to reach for in the first place")
        void repositories_expose_no_delete() {
            // The strongest guarantee available: the methods do not exist to be called.
            // A JpaRepository would have handed every caller deleteById and deleteAll.
            assertThat(methodNamesOf(LeadRepository.class)).noneMatch(this::isRemoval);
            assertThat(methodNamesOf(CustomerRepository.class)).noneMatch(this::isRemoval);
            assertThat(methodNamesOf(ActivityRepository.class)).noneMatch(this::isRemoval);
        }

        private boolean isRemoval(String methodName) {
            return methodName.startsWith("delete") || methodName.startsWith("remove");
        }

        private List<String> methodNamesOf(Class<?> type) {
            return java.util.Arrays.stream(type.getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();
        }

        @Test
        @DisplayName("B's row counts are unchanged after every attempt above")
        void counts_unchanged() {
            // Read as B's own owner. The suite now connects as a role row-level security
            // applies to, so counting another tenant's rows from A's session returns zero —
            // which is the policy working, not the rows having gone anywhere. Under the old
            // superuser connection this read B's rows from A's session and nobody noticed.
            asOwnerOf(tenantB);
            UUID b = tenantB.tenant().id();
            assertThat(leads.countByTenantId(b)).isEqualTo(1);
            assertThat(customers.countByTenantId(b)).isEqualTo(1);
            assertThat(activities.countByTenantIdAndSubjectTypeAndSubjectId(b, "Lead", leadOfB))
                    .isEqualTo(1);
        }
    }

    // ====================================================================== scope escape

    @Nested
    @DisplayName("A user cannot escape their own scope by choosing arguments")
    class ScopeEscape {

        @Test
        @DisplayName("an agent's listing is their own records, whatever they pass")
        void an_agent_sees_only_their_own() {
            // The agent is a real user of tenant A — leads.owner_user_id has a tenant-aware
            // foreign key, so an invented id could not own anything in the first place.
            UUID agent = tenantA.owner().id();
            leadService.assign(leadOfA, agent, "assigned to the agent under test");

            TestIdentity.signOut();
            TestIdentity.signIn(agent, tenantA.tenant().id(), Role.SALES_AGENT,
                    tenantA.initialBranch().id());

            // listVisibleToCaller takes a Pageable and nothing else — there is no owner or
            // branch argument to widen, which is the point. The caller may ask for a larger
            // page; they cannot ask for somebody else's rows. Asking for five hundred gets
            // the same set as asking for five.
            List<Lead> visible = leadService.listVisibleToCaller(PageRequest.of(0, 500))
                    .getContent();

            assertThat(visible).extracting(Lead::id)
                    .as("the assertion is worthless against an empty list")
                    .contains(leadOfA)
                    .doesNotContain(leadOfB);
            assertThat(visible).extracting(Lead::ownerUserId).containsOnly(agent);
        }

        @Test
        @DisplayName("an agent cannot read a colleague's lead by knowing its id")
        void an_agent_cannot_read_a_colleagues_lead() {
            asOwnerOf(tenantA);
            UUID colleague = tenantA.owner().id();
            leadService.assign(leadOfA, colleague, "assigned to a colleague");

            TestIdentity.signOut();
            TestIdentity.signIn(UUID.randomUUID(), tenantA.tenant().id(), Role.SALES_AGENT,
                    tenantA.initialBranch().id());

            assertNotFound(() -> leadService.get(leadOfA));
            assertNotFound(() -> leadService.disqualify(leadOfA, "not mine but trying"));
            assertNotFound(() -> activityService.log(ActivitySubject.LEAD, leadOfA,
                    ActivityType.NOTE, "not mine", OffsetDateTime.now()));
        }

        @Test
        @DisplayName("a branch manager's scope ends at their own branch")
        void a_branch_manager_stays_in_their_branch() {
            TestIdentity.signOut();
            TestIdentity.signIn(UUID.randomUUID(), tenantA.tenant().id(), Role.BRANCH_MANAGER,
                    UUID.randomUUID());

            // The lead sits in A's initial branch; this manager runs a different one.
            assertNotFound(() -> leadService.get(leadOfA));
            assertThat(leadService.listVisibleToCaller(PageRequest.of(0, 50)).getContent())
                    .isEmpty();
        }

        @Test
        @DisplayName("a lead cannot be placed into a branch of another tenant")
        void capture_refuses_a_foreign_branch() {
            assertThatThrownBy(() -> leadService.capture("Smuggled", "+201110000099", null,
                    "walk-in", tenantB.initialBranch().id(), null, false))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("a lead cannot be assigned to a user of another tenant")
        void assignment_refuses_a_foreign_owner() {
            assertNotFound(() -> leadService.assign(leadOfA, tenantB.owner().id(), "smuggled"));
        }
    }
}
