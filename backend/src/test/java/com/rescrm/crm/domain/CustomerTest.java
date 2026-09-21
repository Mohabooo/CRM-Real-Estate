package com.rescrm.crm.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The customer record, and in particular what conversion carries across (E2-S4).
 */
@DisplayName("Customer")
class CustomerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private static Lead qualifiedLead() {
        Lead lead = Lead.capture(TENANT, "Mona Fahmy", "0100 123 4567", "mona@example.com",
                "facebook", UUID.randomUUID(), null);
        lead.assignTo(OWNER, null);
        lead.recordFirstContact(OffsetDateTime.now());
        lead.captureInterest("{\"budget\":2000000}");
        lead.qualify();
        return lead;
    }

    @Test
    @DisplayName("conversion carries the contact details and links back to the lead")
    void conversion_carries_contact_details() {
        Lead lead = qualifiedLead();

        Customer customer = Customer.fromLead(lead, null, null, "12 Nile St", null);

        assertThat(customer.tenantId()).isEqualTo(lead.tenantId());
        assertThat(customer.nameEn())
                .as("the lead's name is the customer's name until someone supplies a better one")
                .isEqualTo("Mona Fahmy");
        assertThat(customer.phone()).isEqualTo(lead.phone());
        assertThat(customer.phoneNormalized()).isEqualTo("+201001234567");
        assertThat(customer.email()).isEqualTo("mona@example.com");
        assertThat(customer.address()).isEqualTo("12 Nile St");
        assertThat(customer.sourceLeadId())
                .as("E2-S4: the records must link")
                .isEqualTo(lead.id());
        assertThat(customer.status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("a name supplied at conversion wins over the lead's")
    void supplied_name_wins() {
        Customer customer = Customer.fromLead(qualifiedLead(), "منى فهمي", "Mona A. Fahmy",
                null, null);

        assertThat(customer.nameEn()).isEqualTo("Mona A. Fahmy");
        assertThat(customer.nameAr()).isEqualTo("منى فهمي");
    }

    @Test
    @DisplayName("a customer needs a name in at least one language, not both")
    void one_language_is_enough() {
        Customer arabicOnly = Customer.create(TENANT, "منى فهمي", null, "01001234567",
                null, null, null);
        assertThat(arabicOnly.displayName()).isEqualTo("منى فهمي");

        Customer englishOnly = Customer.create(TENANT, null, "Mona Fahmy", "01001234567",
                null, null, null);
        assertThat(englishOnly.displayName()).isEqualTo("Mona Fahmy");

        assertThatThrownBy(() -> Customer.create(TENANT, "  ", " ", "01001234567", null,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a directly created customer has no source lead")
    void direct_creation_has_no_source_lead() {
        Customer customer = Customer.create(TENANT, null, "Walk-in Buyer", "01112223344",
                null, null, null);

        assertThat(customer.sourceLeadId()).isNull();
        assertThat(customer.phoneNormalized()).isEqualTo("+201112223344");
    }

    @Test
    @DisplayName("the national identifier is only ever held as ciphertext")
    void national_id_is_held_as_ciphertext() {
        Customer customer = Customer.create(TENANT, null, "Mona Fahmy", "01001234567", null,
                null, "Y2lwaGVydGV4dA==");

        assertThat(customer.hasNationalId()).isTrue();
        assertThat(customer.nationalIdCiphertext()).isEqualTo("Y2lwaGVydGV4dA==");

        customer.replaceNationalIdCiphertext(null);
        assertThat(customer.hasNationalId()).isFalse();
    }

    @Test
    @DisplayName("an update cannot remove the last remaining name")
    void update_cannot_erase_every_name() {
        Customer customer = Customer.create(TENANT, null, "Mona Fahmy", "01001234567", null,
                null, null);

        assertThatThrownBy(() -> customer.updateDetails(null, "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(customer.nameEn())
                .as("a rejected update must leave the record as it was")
                .isEqualTo("Mona Fahmy");
    }

    @Test
    @DisplayName("changing the phone re-normalises the match key")
    void phone_change_renormalises() {
        Customer customer = Customer.create(TENANT, null, "Mona Fahmy", "01001234567", null,
                null, null);

        customer.updateDetails(null, null, "+20 111 222 3344", null, null);

        assertThat(customer.phone()).isEqualTo("+20 111 222 3344");
        assertThat(customer.phoneNormalized()).isEqualTo("+201112223344");
    }
}
