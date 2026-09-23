package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Phase;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhaseRepository extends Repository<Phase, UUID> {

    Phase save(Phase phase);

    /**
     * Writes the row now rather than at commit, so a unique-constraint violation
     * surfaces where the service translates it instead of inside the commit.
     */
    Phase saveAndFlush(Phase phase);

    Optional<Phase> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Phase> findAllByTenantIdAndProjectIdOrderByNameAsc(UUID tenantId, UUID projectId);

    long countByTenantId(UUID tenantId);
}
