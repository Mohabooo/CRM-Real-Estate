package com.rescrm.crm.service;

import com.rescrm.crm.domain.Activity;
import com.rescrm.crm.domain.ActivitySubject;
import com.rescrm.crm.domain.ActivityType;
import com.rescrm.crm.repository.ActivityRepository;
import com.rescrm.crm.repository.CustomerRepository;
import com.rescrm.crm.repository.LeadRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The contact timeline (E2-S3).
 *
 * <p>The subject is checked to exist and to be visible before anything is written, so an
 * activity cannot be attached to another tenant's lead or to an id that was guessed.
 */
@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final LeadRepository leads;
    private final CustomerRepository customers;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final Clock clock;

    public ActivityService(ActivityRepository activities, LeadRepository leads,
                           CustomerRepository customers, AuthorizationService authorization,
                           AuditWriter audit, Clock clock) {
        this.activities = activities;
        this.leads = leads;
        this.customers = customers;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Activity log(ActivitySubject subject, UUID subjectId, ActivityType type, String body,
                        OffsetDateTime occurredAt) {
        UUID tenantId = TenantContext.require();
        requireVisibleSubject(tenantId, subject, subjectId);

        UUID actor = SecurityContext.require().userId();
        Activity activity = Activity.log(tenantId, subject, subjectId, type, body,
                occurredAt == null ? OffsetDateTime.now(clock) : occurredAt, actor);
        activity.recordActor(actor);
        Activity saved = activities.save(activity);

        audit.recordCreation(AuditAction.ACTIVITY_LOGGED, subject.code(), subjectId, Map.of(
                "activityId", saved.id().toString(),
                "type", saved.type().code(),
                "occurredAt", saved.occurredAt().toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Activity> timeline(ActivitySubject subject, UUID subjectId) {
        UUID tenantId = TenantContext.require();
        requireVisibleSubject(tenantId, subject, subjectId);
        return activities.findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                tenantId, subject.code(), subjectId);
    }

    /** Doc 18: a lead reaches 'contacted' only once at least one activity exists. */
    @Transactional(readOnly = true)
    public boolean hasAnyActivity(UUID tenantId, ActivitySubject subject, UUID subjectId) {
        return activities.countByTenantIdAndSubjectTypeAndSubjectId(
                tenantId, subject.code(), subjectId) > 0;
    }

    private void requireVisibleSubject(UUID tenantId, ActivitySubject subject, UUID subjectId) {
        switch (subject) {
            case LEAD -> {
                var lead = leads.findByTenantIdAndId(tenantId, subjectId)
                        .orElseThrow(() -> ApiException.notFound("Lead"));
                authorization.requireRecordVisible(lead.ownerUserId(), lead.branchId(), "Lead");
            }
            case CUSTOMER -> customers.findByTenantIdAndId(tenantId, subjectId)
                    .orElseThrow(() -> ApiException.notFound("Customer"));
        }
    }
}
