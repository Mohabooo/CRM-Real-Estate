package com.rescrm.identity.repository;

import com.rescrm.identity.domain.User;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped throughout; see {@link BranchRepository} for why the marker interface. */
public interface UserRepository extends Repository<User, UUID> {

    User save(User user);

    Optional<User> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    List<User> findAllByTenantIdOrderByNameAsc(UUID tenantId);

    /** The listing a branch-scoped caller gets; the branch is supplied, never assumed. */
    List<User> findAllByTenantIdAndBranchIdOrderByNameAsc(UUID tenantId, UUID branchId);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    long countByTenantId(UUID tenantId);
}
