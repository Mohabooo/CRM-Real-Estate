package com.rescrm.crm.repository;

import com.rescrm.crm.domain.Activity;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append and read only. No update or delete method exists, matching the entity's immutability
 * and for the same reason: an editable timeline is a record of what someone later wished had
 * happened.
 */
public interface ActivityRepository extends Repository<Activity, UUID> {

    Activity save(Activity activity);

    List<Activity> findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            UUID tenantId, String subjectType, UUID subjectId);

    long countByTenantIdAndSubjectTypeAndSubjectId(UUID tenantId, String subjectType,
                                                   UUID subjectId);
}
