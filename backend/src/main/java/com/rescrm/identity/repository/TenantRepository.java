package com.rescrm.identity.repository;

import com.rescrm.identity.domain.Tenant;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenants are the isolation root, so looking one up by id <em>is</em> the tenant scope.
 *
 * <p>Like every repository in this module it extends the bare {@link Repository} marker rather
 * than {@code JpaRepository}. That is deliberate: inheriting {@code findAll()} would put an
 * unscoped query one keystroke away, and the point of doc 22 section 4's second isolation
 * layer is that application code cannot express a cross-tenant read by accident.
 */
public interface TenantRepository extends Repository<Tenant, UUID> {

    Tenant save(Tenant tenant);

    /**
     * Writes now rather than at commit, so a duplicate slug surfaces as a catchable
     * exception inside provisioning rather than at commit time, past every catch block.
     */
    Tenant saveAndFlush(Tenant tenant);

    Optional<Tenant> findById(UUID id);

    boolean existsById(UUID id);
}
