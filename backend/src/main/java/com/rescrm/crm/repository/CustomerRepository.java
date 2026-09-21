package com.rescrm.crm.repository;

import com.rescrm.crm.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped throughout; see {@link LeadRepository} for why the marker interface. */
public interface CustomerRepository extends Repository<Customer, UUID> {

    Customer save(Customer customer);

    Optional<Customer> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Customer> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    /** Duplicate detection on the customer book (doc 19: warn, never auto-merge). */
    List<Customer> findAllByTenantIdAndPhoneNormalized(UUID tenantId, String phoneNormalized);

    Optional<Customer> findByTenantIdAndSourceLeadId(UUID tenantId, UUID sourceLeadId);

    long countByTenantId(UUID tenantId);
}
