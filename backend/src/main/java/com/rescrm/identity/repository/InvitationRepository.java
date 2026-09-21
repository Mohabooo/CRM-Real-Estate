package com.rescrm.identity.repository;

import com.rescrm.identity.domain.Invitation;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped throughout, with one deliberate exception documented below. */
public interface InvitationRepository extends Repository<Invitation, UUID> {

    Invitation save(Invitation invitation);

    Optional<Invitation> findByTenantIdAndId(UUID tenantId, UUID id);

    List<Invitation> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Invitation> findByTenantIdAndEmailAndAcceptedAtIsNull(UUID tenantId, String email);

    /**
     * The one lookup that is not tenant-scoped, and the only one that may not be.
     *
     * <p>Someone accepting an invitation has no account and no session, so there is no tenant
     * to scope by — the token is what establishes which tenant they are joining. The hash is
     * globally unique by constraint, so this resolves to at most one row, and the caller reads
     * the tenant from the invitation rather than from anything the client supplied.
     */
    Optional<Invitation> findByTokenHash(String tokenHash);
}
