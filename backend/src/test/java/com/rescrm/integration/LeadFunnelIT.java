package com.rescrm.integration;

import com.rescrm.crm.domain.ActivitySubject;
import com.rescrm.crm.domain.ActivityType;
import com.rescrm.crm.domain.Customer;
import com.rescrm.crm.domain.Lead;
import com.rescrm.crm.repository.CustomerRepository;
import com.rescrm.crm.repository.LeadRepository;
import com.rescrm.crm.service.ActivityService;
import com.rescrm.crm.service.CustomerService;
import com.rescrm.crm.service.DuplicateWarningException;
import com.rescrm.crm.service.LeadService;
import com.rescrm.identity.service.TenantProvisioningService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditEventRepository;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.Role;
import com.rescrm.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Epic 2's stories end to end, against a real PostgreSQL: capture with duplicate warning
 * (E2-S1), assignment and bulk reassignment (E2-S2), the contact timeline and stale queue
 * (E2-S3), and conversion (E2-S4).
 */
@DisplayName("Lead funnel")
class LeadFunnelIT extends AbstractPostgresIT {

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private LeadService leadService;
    @Autowired private CustomerService customerService;
    @Autowired private ActivityService activityService;
    @Autowired private LeadRepository leads;
    @Autowired private CustomerRepository customers;
    @Autowired private AuditEventRepository auditEvents;

    private TenantProvisioningService.ProvisionedTenant tenant;
    private UUID owner;
    private UUID branch;

    @BeforeEach
    void provision() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        tenant = provisioning.provision("Funnel " + unique, "own_inventory", "Main",
                "Owner", "funnel-" + unique + "@example.com", "a-long-enough-password");
        owner = tenant.owner().id();
        branch = tenant.initialBranch().id();
        TestIdentity.signOut();
        TestIdentity.signIn(owner, tenant.tenant().id(), Role.OWNER, null);
    }

    @AfterEach
    void signOut() {
        TestIdentity.signOut();
    }

    private Lead capture(String name, String phone) {
        return leadService.capture(name, phone, null, "walk-in", branch, null, false);
    }

    private Lead qualified(String name, String phone) {
        Lead lead = capture(name, phone);
        leadService.assign(lead.id(), owner, "initial assignment");
        activityService.log(ActivitySubject.LEAD, lead.id(), ActivityType.CALL, "spoke", null);
        leadService.recordContact(lead.id());
        leadService.captureInterest(lead.id(), "{\"budget\":2000000}");
        return leadService.qualify(lead.id());
    }

    // ============================================================================== E2-S1

    @Nested
    @DisplayName("E2-S1 capture")
    class Capture {

        @Test
        @DisplayName("a lead is stored with both phone spellings and an audit entry")
        void capture_stores_and_audits() {
            Lead lead = capture("Mona Fahmy", "0100 987 6543");

            assertThat(lead.phone()).isEqualTo("0100 987 6543");
            assertThat(lead.phoneNormalized()).isEqualTo("+201009876543");
            assertThat(lead.createdByUserId()).isEqualTo(owner);

            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Lead", lead.id()))
                    .extracting(event -> event.action())
                    .contains(AuditAction.LEAD_CAPTURED.name());
        }

        @Test
        @DisplayName("a duplicate phone warns with the matches, and never blocks")
        void duplicate_warns_then_proceeds_on_confirmation() {
            Lead first = capture("Mona Fahmy", "01009876543");

            // Written differently on purpose: the warning has to survive normalisation.
            assertThatThrownBy(() -> capture("M. Fahmy", "+20 100 987 6543"))
                    .isInstanceOf(DuplicateWarningException.class)
                    .satisfies(thrown -> {
                        ApiException api = (ApiException) thrown;
                        assertThat(api.code()).isEqualTo(ErrorCode.CONFLICT);
                        assertThat(api.details()).containsKey("duplicates");
                    });

            // Doc 07 A5: the system warns and proceeds on confirmation. Two family members
            // really do share a number, and refusing the second teaches people to type a
            // fake one.
            Lead second = leadService.capture("M. Fahmy", "+20 100 987 6543", null, "walk-in",
                    branch, null, true);

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(leads.findAllByTenantIdAndPhoneNormalized(tenant.tenant().id(),
                    "+201009876543")).hasSize(2);
        }

        @Test
        @DisplayName("a lead duplicating an existing customer is warned about too")
        void duplicates_span_both_books() {
            customerService.create(null, "Existing Customer", "01009876543", null, null, null,
                    false);

            assertThatThrownBy(() -> capture("Same Number", "01009876543"))
                    .isInstanceOf(DuplicateWarningException.class);
        }
    }

    // ============================================================================== E2-S2

    @Nested
    @DisplayName("E2-S2 assignment")
    class Assignment {

        @Test
        @DisplayName("assignment moves the lead to assigned and records who did it")
        void assignment_is_audited() {
            Lead lead = capture("Mona Fahmy", "01009876543");

            Lead assigned = leadService.assign(lead.id(), owner, "round robin");

            assertThat(assigned.stage().code()).isEqualTo("assigned");
            assertThat(assigned.ownerUserId()).isEqualTo(owner);
            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Lead", lead.id()))
                    .extracting(event -> event.action())
                    .contains(AuditAction.LEAD_ASSIGNED.name());
        }

        @Test
        @DisplayName("a fifty-lead bulk reassignment is one operation and one audit entry")
        void bulk_reassignment_is_one_auditable_operation() {
            List<UUID> fifty = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                fifty.add(leadService.capture("Lead " + index,
                        "+2010000%05d".formatted(index), null, "import", branch, null, true)
                        .id());
            }

            int moved = leadService.bulkReassign(fifty, owner, "territory change");

            assertThat(moved).isEqualTo(50);
            fifty.forEach(id -> assertThat(leadService.get(id).ownerUserId()).isEqualTo(owner));

            long entries = auditEvents.findForTenant(tenant.tenant().id()).stream()
                    .filter(event -> AuditAction.LEAD_BULK_REASSIGNED.name().equals(event.action()))
                    .count();
            assertThat(entries)
                    .as("one decision, one entry — not fifty coincidences")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a bulk reassignment requires a reason")
        void bulk_reassignment_requires_a_reason() {
            Lead lead = capture("Mona Fahmy", "01009876543");

            assertThatThrownBy(() -> leadService.bulkReassign(List.of(lead.id()), owner, "  "))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("an unknown id fails the whole batch rather than moving the rest")
        void an_unknown_id_fails_the_whole_batch() {
            Lead lead = capture("Mona Fahmy", "01009876543");

            assertThatThrownBy(() -> leadService.bulkReassign(
                    List.of(lead.id(), UUID.randomUUID()), owner, "territory change"))
                    .isInstanceOf(ApiException.class);

            assertThat(leadService.get(lead.id()).ownerUserId())
                    .as("partial success would leave the manager unsure which half moved")
                    .isNull();
        }
    }

    // ============================================================================== E2-S3

    @Nested
    @DisplayName("E2-S3 timeline and follow-up")
    class Timeline {

        @Test
        @DisplayName("a lead cannot be marked contacted before the contact is logged")
        void contact_requires_a_logged_activity() {
            Lead lead = capture("Mona Fahmy", "01009876543");
            leadService.assign(lead.id(), owner, "initial");

            assertThatThrownBy(() -> leadService.recordContact(lead.id()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .as("'contacted' claims somebody spoke to this person")
                            .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

            activityService.log(ActivitySubject.LEAD, lead.id(), ActivityType.CALL,
                    "no answer, left a message", null);

            assertThat(leadService.recordContact(lead.id()).stage().code())
                    .isEqualTo("contacted");
            assertThat(leadService.get(lead.id()).firstContactAt()).isNotNull();
        }

        @Test
        @DisplayName("the timeline is newest first and covers every activity type")
        void timeline_is_newest_first() {
            Lead lead = capture("Mona Fahmy", "01009876543");
            OffsetDateTime now = OffsetDateTime.now();

            activityService.log(ActivitySubject.LEAD, lead.id(), ActivityType.CALL, "call",
                    now.minusDays(3));
            activityService.log(ActivitySubject.LEAD, lead.id(), ActivityType.WHATSAPP,
                    "sent brochure", now.minusDays(2));
            activityService.log(ActivitySubject.LEAD, lead.id(), ActivityType.MEETING,
                    "site visit", now.minusDays(1));
            activityService.log(ActivitySubject.LEAD, lead.id(), ActivityType.NOTE,
                    "prefers a corner unit", now);

            assertThat(activityService.timeline(ActivitySubject.LEAD, lead.id()))
                    .extracting(activity -> activity.type().code())
                    .containsExactly("note", "meeting", "whatsapp", "call");
        }

        @Test
        @DisplayName("the stale queue holds only active leads whose next action has passed")
        void stale_queue_is_overdue_and_active_only() {
            Lead overdue = capture("Overdue", "01009876541");
            Lead upcoming = capture("Upcoming", "01009876542");
            Lead abandoned = capture("Abandoned", "01009876544");

            leadService.setNextAction(overdue.id(), OffsetDateTime.now().minusDays(2));
            leadService.setNextAction(upcoming.id(), OffsetDateTime.now().plusDays(2));
            leadService.setNextAction(abandoned.id(), OffsetDateTime.now().minusDays(5));
            leadService.disqualify(abandoned.id(), "wrong city");

            assertThat(leadService.staleQueue()).extracting(Lead::id)
                    .contains(overdue.id())
                    .doesNotContain(upcoming.id())
                    .as("a lead out of the funnel must not keep appearing in the queue")
                    .doesNotContain(abandoned.id());
        }
    }

    // ============================================================================== E2-S4

    @Nested
    @DisplayName("E2-S4 conversion")
    class Conversion {

        @Test
        @DisplayName("contact details carry across, the records link, and the lead is retained")
        void conversion_links_and_retains() {
            Lead lead = qualified("Mona Fahmy", "0100 987 6543");

            Customer customer = customerService.convertLead(lead.id(), "منى فهمي", null,
                    "12 Nile St", null);

            assertThat(customer.phoneNormalized()).isEqualTo(lead.phoneNormalized());
            assertThat(customer.nameEn()).isEqualTo("Mona Fahmy");
            assertThat(customer.nameAr()).isEqualTo("منى فهمي");
            assertThat(customer.sourceLeadId()).isEqualTo(lead.id());

            Lead afterwards = leadService.get(lead.id());
            assertThat(afterwards.status().code())
                    .as("the lead leaves the funnel; it is not deleted")
                    .isEqualTo("converted");
            assertThat(afterwards.name()).isEqualTo("Mona Fahmy");

            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Lead", lead.id()))
                    .extracting(event -> event.action())
                    .contains(AuditAction.LEAD_CONVERTED.name());
        }

        @Test
        @DisplayName("an unqualified lead cannot be converted")
        void conversion_requires_qualification() {
            Lead lead = capture("Mona Fahmy", "01009876543");

            assertThatThrownBy(() -> customerService.convertLead(lead.id(), null, null, null,
                    null))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION));
        }

        @Test
        @DisplayName("the same lead cannot be converted twice")
        void conversion_happens_once() {
            Lead lead = qualified("Mona Fahmy", "0100 987 6543");
            customerService.convertLead(lead.id(), null, null, null, null);

            assertThatThrownBy(() -> customerService.convertLead(lead.id(), null, null, null,
                    null))
                    .isInstanceOf(ApiException.class);

            assertThat(customers.countByTenantId(tenant.tenant().id())).isEqualTo(1);
        }

        @Test
        @DisplayName("the national identifier is stored encrypted and revealed only on request")
        void national_id_is_encrypted_and_audited() {
            Lead lead = qualified("Mona Fahmy", "0100 987 6543");

            Customer customer = customerService.convertLead(lead.id(), null, null, null,
                    "29801011234567");

            assertThat(customer.nationalIdCiphertext())
                    .as("the number itself must never be what is stored")
                    .isNotNull()
                    .isNotEqualTo("29801011234567");

            assertThat(customerService.revealNationalId(customer.id(), "mortgage paperwork"))
                    .isEqualTo("29801011234567");

            assertThat(auditEvents.findTrail(tenant.tenant().id(), "Customer", customer.id()))
                    .extracting(event -> event.action())
                    .as("doc 28 section 13: the access is recorded, not just the change")
                    .contains(AuditAction.CUSTOMER_NATIONAL_ID_ACCESSED.name());
        }

        @Test
        @DisplayName("a sales agent may not reveal a national identifier at all")
        void reveal_is_restricted() {
            Lead lead = qualified("Mona Fahmy", "0100 987 6543");
            Customer customer = customerService.convertLead(lead.id(), null, null, null,
                    "29801011234567");

            TestIdentity.signOut();
            TestIdentity.signIn(owner, tenant.tenant().id(), Role.SALES_AGENT, branch);

            assertThatThrownBy(() -> customerService.revealNationalId(customer.id(), "curious"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).code())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }
    }
}
