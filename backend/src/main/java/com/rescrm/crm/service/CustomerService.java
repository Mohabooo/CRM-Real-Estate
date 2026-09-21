package com.rescrm.crm.service;

import com.rescrm.crm.domain.Customer;
import com.rescrm.crm.domain.Lead;
import com.rescrm.crm.repository.CustomerRepository;
import com.rescrm.crm.repository.LeadRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.crypto.FieldEncryptor;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The customer book (E2-S4 and doc 19's customer requirements).
 *
 * <p>The national identifier is encrypted on write and decrypted only through
 * {@link #revealNationalId}, which is restricted by role and writes an audit entry every time.
 * Doc 19 and doc 28 section 13 both require that access to be recorded; making it a separate,
 * explicit call is what makes recording it possible at all — a field decrypted on every load
 * has no access event to audit.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final LeadRepository leads;
    private final DuplicateDetection duplicates;
    private final FieldEncryptor encryptor;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public CustomerService(CustomerRepository customers, LeadRepository leads,
                           DuplicateDetection duplicates, FieldEncryptor encryptor,
                           AuthorizationService authorization, AuditWriter audit) {
        this.customers = customers;
        this.leads = leads;
        this.duplicates = duplicates;
        this.encryptor = encryptor;
        this.authorization = authorization;
        this.audit = audit;
    }

    /**
     * E2-S4: convert a qualified lead. Contact details carry across, the records link, and the
     * lead leaves the funnel without deletion.
     */
    @Transactional
    public Customer convertLead(UUID leadId, String nameAr, String nameEn, String address,
                                String nationalId) {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();

        Lead lead = leads.findByTenantIdAndId(tenantId, leadId)
                .orElseThrow(() -> ApiException.notFound("Lead"));
        authorization.requireRecordVisible(lead.ownerUserId(), lead.branchId(), "Lead");

        // The partial unique index enforces this too; checking first turns a constraint
        // violation into an answer the caller can act on.
        customers.findByTenantIdAndSourceLeadId(tenantId, leadId).ifPresent(existing -> {
            throw new ApiException(ErrorCode.CONFLICT,
                    "That lead has already been converted", Map.of(
                            "customerId", existing.id().toString()));
        });

        UUID actor = SecurityContext.require().userId();

        // The lead's own guard runs first. Rolling back would undo a customer written before
        // it, but "nothing was persisted" and "nothing was attempted" are different things to
        // debug, and the second is the one worth having.
        try {
            lead.markConverted();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        }

        Customer customer;
        try {
            customer = Customer.fromLead(lead, nameAr, nameEn, address,
                    encryptor.encrypt(nationalId));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        customer.recordActor(actor, true);
        Customer savedCustomer = customers.save(customer);

        lead.recordActor(actor, false);
        leads.save(lead);

        audit.recordCreation(AuditAction.CUSTOMER_CREATED, "Customer", savedCustomer.id(),
                Map.of("via", "conversion", "sourceLeadId", lead.id().toString()));
        audit.record(AuditAction.LEAD_CONVERTED, "Lead", lead.id(),
                Map.of("status", "active"),
                Map.of("status", lead.status().code(),
                        "customerId", savedCustomer.id().toString()), null);
        return savedCustomer;
    }

    /** Doc 19: a customer may also be created directly, with the same duplicate warning. */
    @Transactional
    public Customer create(String nameAr, String nameEn, String phone, String email,
                           String address, String nationalId, boolean confirmedDuplicate) {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();

        duplicates.requireConfirmationIfDuplicated(tenantId, phone, null, confirmedDuplicate);

        Customer customer;
        try {
            customer = Customer.create(tenantId, nameAr, nameEn, phone, email, address,
                    encryptor.encrypt(nationalId));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        customer.recordActor(SecurityContext.require().userId(), true);
        Customer saved = customers.save(customer);

        audit.recordCreation(AuditAction.CUSTOMER_CREATED, "Customer", saved.id(),
                Map.of("via", "direct", "phone", saved.phoneNormalized()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Customer get(UUID customerId) {
        return customers.findByTenantIdAndId(TenantContext.require(), customerId)
                .orElseThrow(() -> ApiException.notFound("Customer"));
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(Pageable pageable) {
        return customers.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.require(), pageable);
    }

    @Transactional(readOnly = true)
    public List<DuplicateMatch> findDuplicates(String phone) {
        return duplicates.findMatches(TenantContext.require(), phone, null);
    }

    @Transactional
    public Customer updateDetails(UUID customerId, String nameAr, String nameEn, String phone,
                                  String email, String address) {
        UUID tenantId = TenantContext.require();
        Customer customer = get(customerId);
        if (phone != null) {
            duplicates.requireConfirmationIfDuplicated(tenantId, phone, customerId, true);
        }
        try {
            customer.updateDetails(nameAr, nameEn, phone, email, address);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        customer.recordActor(SecurityContext.require().userId(), false);
        Customer saved = customers.save(customer);
        audit.record(AuditAction.CUSTOMER_UPDATED, "Customer", saved.id(), null,
                Map.of("phone", saved.phoneNormalized()), null);
        return saved;
    }

    /**
     * Reveals the national identifier. Restricted, and audited on every call.
     *
     * <p>Doc 19 F3/F9 and doc 28 section 13 require access to be recorded, not merely the
     * change. The audit entry is written before the value is returned, so a failure to record
     * the access prevents the access.
     */
    @Transactional
    public String revealNationalId(UUID customerId, String reason) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.FINANCE,
                Role.PLATFORM_ADMIN);
        Customer customer = get(customerId);
        if (!customer.hasNationalId()) {
            throw ApiException.notFound("National identifier");
        }

        audit.record(AuditAction.CUSTOMER_NATIONAL_ID_ACCESSED, "Customer", customer.id(),
                null, Map.of("accessed", true), reason);

        return encryptor.decrypt(customer.nationalIdCiphertext());
    }

    @Transactional
    public Customer setNationalId(UUID customerId, String nationalId) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.FINANCE,
                Role.PLATFORM_ADMIN);
        Customer customer = get(customerId);
        customer.replaceNationalIdCiphertext(encryptor.encrypt(nationalId));
        customer.recordActor(SecurityContext.require().userId(), false);
        Customer saved = customers.save(customer);
        audit.record(AuditAction.CUSTOMER_UPDATED, "Customer", saved.id(), null,
                Map.of("nationalIdSet", saved.hasNationalId()), null);
        return saved;
    }
}
