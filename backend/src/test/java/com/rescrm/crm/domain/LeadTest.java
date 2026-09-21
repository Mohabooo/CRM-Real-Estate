package com.rescrm.crm.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lead funnel's state machine (doc 18, section 1).
 *
 * <p>These run with no Spring context and no database, because the rules are the entity's and
 * a test that needed infrastructure to check them would be evidence that they had leaked into
 * a service. The database's CHECK constraints assert the same invariants independently — see
 * {@code V3__crm_core.sql} — so a transition removed here still fails there.
 */
@DisplayName("Lead")
class LeadTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();

    private static Lead newLead() {
        return Lead.capture(TENANT, "Mona Fahmy", "0100 123 4567", "Mona@Example.COM  ",
                "facebook", BRANCH, null);
    }

    private static Lead qualifiedLead() {
        Lead lead = newLead();
        lead.assignTo(OWNER, BRANCH);
        lead.recordFirstContact(OffsetDateTime.now());
        lead.captureInterest("{\"budget\":2000000}");
        lead.qualify();
        return lead;
    }

    @Nested
    @DisplayName("on capture")
    class OnCapture {

        @Test
        @DisplayName("starts new and active, with the phone kept as typed and normalised")
        void starts_new_and_active() {
            Lead lead = newLead();

            assertThat(lead.stage()).isEqualTo(LeadStage.NEW);
            assertThat(lead.status()).isEqualTo(LeadStatus.ACTIVE);
            assertThat(lead.ownerUserId()).isNull();
            assertThat(lead.phone())
                    .as("the agent must still recognise what they typed")
                    .isEqualTo("0100 123 4567");
            assertThat(lead.phoneNormalized()).isEqualTo("+201001234567");
        }

        @Test
        @DisplayName("lower-cases the email so two spellings are one address")
        void normalises_email() {
            assertThat(newLead().email()).isEqualTo("mona@example.com");
        }

        @Test
        @DisplayName("defaults interest to an empty object rather than null")
        void interest_defaults_to_empty_object() {
            assertThat(newLead().interest()).isEqualTo("{}");
        }

        @Test
        @DisplayName("refuses a blank name or phone")
        void requires_a_name_and_a_phone() {
            assertThatThrownBy(() -> Lead.capture(TENANT, "  ", "01001234567", null, null,
                    BRANCH, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Lead.capture(TENANT, "Someone", " ", null, null,
                    BRANCH, null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses an email with no domain")
        void refuses_a_malformed_email() {
            assertThatThrownBy(() -> Lead.capture(TENANT, "Someone", "01001234567", "@nope",
                    null, BRANCH, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("stage transitions follow doc 18 and nothing else")
    class Transitions {

        @Test
        @DisplayName("new -> assigned -> contacted -> qualified")
        void the_documented_path_works() {
            Lead lead = newLead();

            lead.assignTo(OWNER, BRANCH);
            assertThat(lead.stage()).isEqualTo(LeadStage.ASSIGNED);
            assertThat(lead.ownerUserId()).isEqualTo(OWNER);

            OffsetDateTime when = OffsetDateTime.now();
            lead.recordFirstContact(when);
            assertThat(lead.stage()).isEqualTo(LeadStage.CONTACTED);
            assertThat(lead.firstContactAt()).isEqualTo(when);

            lead.captureInterest("{\"budget\":2000000}");
            lead.qualify();
            assertThat(lead.stage()).isEqualTo(LeadStage.QUALIFIED);
        }

        @Test
        @DisplayName("a stage cannot be skipped")
        void stages_cannot_be_skipped() {
            Lead lead = newLead();
            assertThatThrownBy(() -> lead.recordFirstContact(OffsetDateTime.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("new");

            lead.assignTo(OWNER, BRANCH);
            lead.captureInterest("{\"budget\":1}");
            assertThatThrownBy(lead::qualify)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assigned");
        }

        @Test
        @DisplayName("qualifying without captured interest is refused")
        void qualification_requires_interest() {
            Lead lead = newLead();
            lead.assignTo(OWNER, BRANCH);
            lead.recordFirstContact(OffsetDateTime.now());

            assertThatThrownBy(lead::qualify)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interest");
        }

        @Test
        @DisplayName("reassignment moves the owner without moving the stage backwards")
        void reassignment_keeps_the_stage() {
            Lead lead = qualifiedLead();
            UUID newOwner = UUID.randomUUID();

            lead.assignTo(newOwner, null);

            assertThat(lead.ownerUserId()).isEqualTo(newOwner);
            assertThat(lead.stage())
                    .as("a reassigned lead has not un-qualified itself")
                    .isEqualTo(LeadStage.QUALIFIED);
        }
    }

    @Nested
    @DisplayName("leaving the funnel")
    class LeavingTheFunnel {

        @Test
        @DisplayName("disqualification requires a reason and clears the next action")
        void disqualification_requires_a_reason() {
            Lead lead = newLead();
            lead.setNextAction(OffsetDateTime.now().plusDays(1));

            assertThatThrownBy(() -> lead.disqualify("  "))
                    .isInstanceOf(IllegalArgumentException.class);

            lead.disqualify("Wrong city");
            assertThat(lead.status()).isEqualTo(LeadStatus.DISQUALIFIED);
            assertThat(lead.disqualifiedReason()).isEqualTo("Wrong city");
            assertThat(lead.nextActionAt())
                    .as("a lead out of the funnel must not keep appearing in the stale queue")
                    .isNull();
        }

        @Test
        @DisplayName("a disqualified lead is retained, not deleted, and keeps its stage")
        void disqualified_lead_is_retained() {
            Lead lead = qualifiedLead();
            lead.disqualify("Budget too low");

            assertThat(lead.stage()).isEqualTo(LeadStage.QUALIFIED);
            assertThat(lead.name()).isEqualTo("Mona Fahmy");
        }

        @Test
        @DisplayName("nothing can be done to a lead that has left the funnel")
        void an_inactive_lead_is_frozen() {
            Lead lead = qualifiedLead();
            lead.disqualify("Budget too low");

            assertThatThrownBy(() -> lead.assignTo(UUID.randomUUID(), null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(lead::markConverted)
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> lead.setNextAction(OffsetDateTime.now()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("reactivation returns it to assigned, with a reason and a new owner")
        void reactivation_returns_it_to_assigned() {
            Lead lead = qualifiedLead();
            lead.disqualify("Budget too low");
            UUID newOwner = UUID.randomUUID();

            lead.reactivate(newOwner, "Budget increased");

            assertThat(lead.status()).isEqualTo(LeadStatus.ACTIVE);
            assertThat(lead.stage()).isEqualTo(LeadStage.ASSIGNED);
            assertThat(lead.ownerUserId()).isEqualTo(newOwner);
            assertThat(lead.disqualifiedReason()).isNull();
        }

        @Test
        @DisplayName("only a disqualified lead can be reactivated")
        void only_a_disqualified_lead_reactivates() {
            Lead lead = newLead();
            assertThatThrownBy(() -> lead.reactivate(OWNER, "why not"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("conversion is refused before qualification")
        void conversion_requires_qualification() {
            Lead lead = newLead();
            lead.assignTo(OWNER, BRANCH);

            assertThatThrownBy(lead::markConverted)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("qualified");
        }

        @Test
        @DisplayName("a converted lead is retained with its stage and history intact")
        void conversion_retains_the_lead() {
            Lead lead = qualifiedLead();
            lead.markConverted();

            assertThat(lead.status()).isEqualTo(LeadStatus.CONVERTED);
            assertThat(lead.stage()).isEqualTo(LeadStage.QUALIFIED);
            assertThat(lead.firstContactAt()).isNotNull();
        }
    }
}
