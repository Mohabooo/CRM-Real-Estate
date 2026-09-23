package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Developer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped throughout, and extends the bare {@link Repository} marker so no unscoped
 * finder exists to call by accident.
 */
public interface DeveloperRepository extends Repository<Developer, UUID> {

    Developer save(Developer developer);

    /**
     * Writes the row now rather than at commit, so a unique-constraint violation
     * surfaces where the service translates it instead of inside the commit.
     */
    Developer saveAndFlush(Developer developer);

    Optional<Developer> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Developer> findAllByTenantIdOrderByNameAsc(UUID tenantId, Pageable pageable);

    List<Developer> findAllByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);

    boolean existsByTenantIdAndId(UUID tenantId, UUID id);

    long countByTenantId(UUID tenantId);
}
