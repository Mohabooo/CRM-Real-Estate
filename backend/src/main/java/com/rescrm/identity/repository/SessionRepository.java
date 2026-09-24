package com.rescrm.identity.repository;

import com.rescrm.identity.domain.Session;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions, tenant-scoped like everything else, with one deliberate exception.
 *
 * <p>{@link #findByTokenHash(String)} is the only finder in the codebase that takes no tenant
 * id, because it is the one query that runs before a tenant is known — resolving a cookie is
 * how the tenant gets established in the first place. It is safe for two reasons that have
 * nothing to do with trusting the caller: the token is 256 bits of {@link java.security.SecureRandom}
 * output, so it cannot be guessed, and the row that comes back is what NAMES the tenant
 * rather than something selected by one. Every query after it is tenant-scoped as usual.
 *
 * <p>Extends the bare {@link Repository} marker, so no unscoped {@code findAll} or
 * {@code deleteAll} exists to reach for by accident.
 */
public interface SessionRepository extends Repository<Session, UUID> {

    Session save(Session session);

    /**
     * Writes now rather than at commit, so a token-hash collision — astronomically unlikely,
     * but a unique constraint all the same — surfaces where the service can react to it.
     */
    Session saveAndFlush(Session session);

    /** The cookie lookup. See the class comment for why this one carries no tenant id. */
    Optional<Session> findByTokenHash(String tokenHash);

    Optional<Session> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Every session a user currently holds, for revoking them all at once. */
    @Query("SELECT s FROM Session s WHERE s.tenantId = :tenantId AND s.userId = :userId "
            + "AND s.revokedAt IS NULL")
    List<Session> findLiveForUser(@Param("tenantId") UUID tenantId,
                                  @Param("userId") UUID userId);

    /**
     * Ends every live session a user holds, in one statement.
     *
     * <p>A statement rather than a loop because deactivation must not depend on how many
     * browsers someone left signed in, and because the row count is a useful audit detail.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Session s SET s.revokedAt = :at, s.revokedReason = :reason "
            + "WHERE s.tenantId = :tenantId AND s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("tenantId") UUID tenantId,
                         @Param("userId") UUID userId,
                         @Param("at") OffsetDateTime at,
                         @Param("reason") String reason);

    long countByTenantId(UUID tenantId);
}
