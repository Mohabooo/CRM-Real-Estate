package com.rescrm.reservations.repository;

import com.rescrm.reservations.domain.Reservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped throughout, and extends the bare {@link Repository} marker so no unscoped
 * finder exists to call by accident.
 */
public interface ReservationRepository extends Repository<Reservation, UUID> {

    Reservation save(Reservation reservation);

    /**
     * Writes now rather than at commit, so the C2 unique-index violation surfaces where the
     * service can turn it into a 409 naming the current holder.
     */
    Reservation saveAndFlush(Reservation reservation);

    Optional<Reservation> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Reservation> findAllByTenantIdOrderByReservedAtDesc(UUID tenantId, Pageable pageable);

    Page<Reservation> findAllByTenantIdAndAgentUserIdOrderByReservedAtDesc(
            UUID tenantId, UUID agentUserId, Pageable pageable);

    Page<Reservation> findAllByTenantIdAndBranchIdOrderByReservedAtDesc(
            UUID tenantId, UUID branchId, Pageable pageable);

    /** The live hold on a unit, if there is one. C2 guarantees there is at most one. */
    @Query("SELECT r FROM Reservation r WHERE r.tenantId = :tenantId AND r.unitId = :unitId "
            + "AND r.status IN ('pending', 'confirmed')")
    Optional<Reservation> findActiveForUnit(@Param("tenantId") UUID tenantId,
                                            @Param("unitId") UUID unitId);

    /**
     * The expiry sweep's working set for one tenant (E4-S2).
     *
     * <p>Tenant-scoped like everything else here. Finding out WHICH tenants to run for is a
     * separate problem with a separate answer — {@code PlatformTenantRegistry} — because a
     * cross-tenant query would see nothing under row-level security in production while
     * appearing to work in tests, where the connection is a superuser.
     */
    @Query("SELECT r FROM Reservation r WHERE r.tenantId = :tenantId "
            + "AND r.status IN ('pending', 'confirmed') AND r.expiresAt <= :asOf "
            + "ORDER BY r.expiresAt ASC")
    List<Reservation> findExpired(@Param("tenantId") UUID tenantId,
                                  @Param("asOf") OffsetDateTime asOf);

    long countByTenantId(UUID tenantId);
}
