package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Phase;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhaseRepository extends Repository<Phase, UUID> {

    Phase save(Phase phase);

    Optional<Phase> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Phase> findAllByTenantIdAndProjectIdOrderByNameAsc(UUID tenantId, UUID projectId);

    long countByTenantId(UUID tenantId);
}
