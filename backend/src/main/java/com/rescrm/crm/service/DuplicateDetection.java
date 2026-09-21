package com.rescrm.crm.service;

import com.rescrm.crm.domain.PhoneNumbers;
import com.rescrm.crm.repository.CustomerRepository;
import com.rescrm.crm.repository.LeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Finds existing leads and customers sharing a phone number (E2-S1).
 *
 * <p>Matching is on the canonical form, which is the whole reason for normalising: the same
 * number typed six ways is one match key, so a duplicate is found regardless of how either
 * record was entered.
 */
@Service
public class DuplicateDetection {

    private final LeadRepository leads;
    private final CustomerRepository customers;

    public DuplicateDetection(LeadRepository leads, CustomerRepository customers) {
        this.leads = leads;
        this.customers = customers;
    }

    /** Matches across both books, excluding one record id when re-checking an edit. */
    @Transactional(readOnly = true)
    public List<DuplicateMatch> findMatches(UUID tenantId, String phone, UUID excludeId) {
        String canonical = PhoneNumbers.normalizeOrNull(phone);
        if (canonical == null) {
            return List.of();
        }

        List<DuplicateMatch> matches = new ArrayList<>();
        leads.findAllByTenantIdAndPhoneNormalized(tenantId, canonical).stream()
                .filter(lead -> !lead.id().equals(excludeId))
                .forEach(lead -> matches.add(new DuplicateMatch(
                        DuplicateMatch.LEAD, lead.id(), lead.name(), lead.phoneNormalized())));

        customers.findAllByTenantIdAndPhoneNormalized(tenantId, canonical).stream()
                .filter(customer -> !customer.id().equals(excludeId))
                .forEach(customer -> matches.add(new DuplicateMatch(
                        DuplicateMatch.CUSTOMER, customer.id(), customer.displayName(),
                        customer.phoneNormalized())));

        return matches;
    }

    /** Throws unless the caller has confirmed, so the warning cannot be skipped by omission. */
    void requireConfirmationIfDuplicated(UUID tenantId, String phone, UUID excludeId,
                                         boolean confirmed) {
        if (confirmed) {
            return;
        }
        List<DuplicateMatch> matches = findMatches(tenantId, phone, excludeId);
        if (!matches.isEmpty()) {
            throw new DuplicateWarningException(matches);
        }
    }
}
