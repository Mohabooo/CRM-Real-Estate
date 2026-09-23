package com.rescrm.inventory.repository;

import com.rescrm.inventory.domain.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends Repository<Project, UUID> {

    Project save(Project project);

    Optional<Project> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Doc 23's {@code GET /projects?developer_id=&status=}. Both filters are optional, and a
     * null means "no filter" rather than "match null" — a distinction worth stating, because
     * the alternative would make an omitted developer filter return only own-inventory
     * projects, which looks like a working filter and is not.
     */
    @Query("SELECT p FROM Project p WHERE p.tenantId = :tenantId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:developerId IS NULL OR p.developerId = :developerId) "
            + "ORDER BY p.createdAt DESC")
    Page<Project> findFiltered(@Param("tenantId") UUID tenantId,
                               @Param("status") String status,
                               @Param("developerId") UUID developerId,
                               Pageable pageable);

    boolean existsByTenantIdAndId(UUID tenantId, UUID id);

    /** Whether any project still points at a developer, so deactivation can warn rather than break. */
    long countByTenantIdAndDeveloperId(UUID tenantId, UUID developerId);

    long countByTenantId(UUID tenantId);
}
