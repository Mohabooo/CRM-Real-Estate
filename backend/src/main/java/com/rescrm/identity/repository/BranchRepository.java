package com.rescrm.identity.repository;

import com.rescrm.identity.domain.Branch;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method names the tenant. There is no finder that does not, and none can be added by
 * inheritance because this extends the marker interface rather than {@code JpaRepository}.
 */
public interface BranchRepository extends Repository<Branch, UUID> {

    Branch save(Branch branch);

    Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Branch> findAllByTenantIdOrderByNameAsc(UUID tenantId);

    List<Branch> findAllByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);

    boolean existsByTenantIdAndId(UUID tenantId, UUID id);

    long countByTenantId(UUID tenantId);
}
