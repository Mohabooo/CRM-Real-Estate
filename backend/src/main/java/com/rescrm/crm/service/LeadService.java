package com.rescrm.crm.service;

import com.rescrm.crm.domain.ActivitySubject;
import com.rescrm.crm.domain.Lead;
import com.rescrm.crm.repository.ActivityRepository;
import com.rescrm.crm.repository.LeadRepository;
import com.rescrm.identity.service.IdentityDirectory;
import com.rescrm.identity.service.IdentityDirectory.DirectoryUser;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lead funnel (doc 20, E2-S1 to E2-S4; doc 18 section 1).
 *
 * <p>Every method takes its tenant from {@link TenantContext} and its caller from
 * {@link SecurityContext}; neither is ever an argument, so no controller can pass one in from
 * a request. The permitted actors for each transition come straight from doc 18's table.
 */
@Service
public class LeadService {

    private final LeadRepository leads;
    private final ActivityRepository activities;
    private final IdentityDirectory directory;
    private final DuplicateDetection duplicates;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final Clock clock;

    public LeadService(LeadRepository leads, ActivityRepository activities,
                       IdentityDirectory directory, DuplicateDetection duplicates,
                       AuthorizationService authorization, AuditWriter audit, Clock clock) {
        this.leads = leads;
        this.activities = activities;
        this.directory = directory;
        this.duplicates = duplicates;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ capture

    /**
     * E2-S1. A duplicate phone raises a warning carrying the matches; the same call with
     * {@code confirmed} set creates the lead anyway. Nothing is ever auto-merged.
     */
    @Transactional
    public Lead capture(String name, String phone, String email, String source, UUID branchId,
                        String interest, boolean confirmedDuplicate) {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();

        UUID branch = branchId != null ? branchId : SecurityContext.require().branchId();
        requireBranchInTenant(tenantId, branch);

        duplicates.requireConfirmationIfDuplicated(tenantId, phone, null, confirmedDuplicate);

        Lead lead = build(tenantId, name, phone, email, source, branch, interest);
        lead.recordActor(SecurityContext.require().userId(), true);
        Lead saved = leads.save(lead);

        audit.recordCreation(AuditAction.LEAD_CAPTURED, "Lead", saved.id(), Map.of(
                "name", saved.name(),
                "phone", saved.phoneNormalized(),
                "stage", saved.stage().code(),
                "duplicateConfirmed", confirmedDuplicate));
        return saved;
    }

    private Lead build(UUID tenantId, String name, String phone, String email, String source,
                       UUID branchId, String interest) {
        try {
            return Lead.capture(tenantId, name, phone, email, source, branchId, interest);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ assignment

    /** E2-S2. Doc 18: assignment is performed by a manager or operations, and is audited. */
    @Transactional
    public Lead assign(UUID leadId, UUID newOwnerUserId, String reason) {
        authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.TEAM_LEADER, Role.OPERATIONS,
                Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();

        Lead lead = require(tenantId, leadId);
        var owner = requireAssignableOwner(tenantId, newOwnerUserId);
        UUID previousOwner = lead.ownerUserId();

        apply(() -> lead.assignTo(owner.id(), owner.branchId()));
        lead.recordActor(SecurityContext.require().userId(), false);
        Lead saved = leads.save(lead);

        audit.record(AuditAction.LEAD_ASSIGNED, "Lead", saved.id(),
                Map.of("ownerUserId", String.valueOf(previousOwner)),
                Map.of("ownerUserId", owner.id().toString(), "stage", saved.stage().code()),
                reason);
        return saved;
    }

    /**
     * E2-S2. A bulk reassignment is one transaction and one audit entry.
     *
     * <p>"50-lead bulk reassign is one auditable operation" is the acceptance criterion, and
     * the two halves matter equally: all fifty move or none do, and the trail records one
     * decision rather than fifty coincidences.
     */
    @Transactional
    public int bulkReassign(Collection<UUID> leadIds, UUID newOwnerUserId, String reason) {
        authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.TEAM_LEADER, Role.OPERATIONS,
                Role.OWNER, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();

        if (leadIds == null || leadIds.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No leads were given to reassign");
        }
        String trimmedReason = reason == null ? null : reason.trim();
        if (trimmedReason == null || trimmedReason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A reason is required for a bulk reassignment");
        }

        var owner = requireAssignableOwner(tenantId, newOwnerUserId);
        List<Lead> found = leads.findAllByTenantIdAndIdIn(tenantId, leadIds);

        // Partial success would leave the manager unsure which half moved, so an unknown or
        // out-of-scope id fails the whole operation rather than being skipped quietly.
        if (found.size() != leadIds.size()) {
            throw ApiException.notFound("One or more leads");
        }
        found.forEach(lead -> authorization.requireRecordVisible(
                lead.ownerUserId(), lead.branchId(), "Lead"));

        UUID actor = SecurityContext.require().userId();
        found.forEach(lead -> {
            apply(() -> lead.assignTo(owner.id(), owner.branchId()));
            lead.recordActor(actor, false);
            leads.save(lead);
        });

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ownerUserId", owner.id().toString());
        after.put("leadCount", found.size());
        after.put("leadIds", found.stream().map(lead -> lead.id().toString()).toList());
        audit.record(AuditAction.LEAD_BULK_REASSIGNED, "Lead", owner.id(), null, after,
                trimmedReason);

        return found.size();
    }

    // ------------------------------------------------------------------ progression

    /**
     * Doc 18: only the owning agent records first contact, and only once the contact itself has
     * been logged.
     *
     * <p>The precondition is the point of the stage: 'contacted' is a claim that somebody spoke
     * to this person, so it has to be backed by an activity on the timeline rather than by a
     * button press. Without the check an agent could clear their queue without making a call.
     */
    @Transactional
    public Lead recordContact(UUID leadId) {
        UUID tenantId = TenantContext.require();
        Lead lead = require(tenantId, leadId);
        requireOwnerOrManager(lead);
        requireLoggedActivity(tenantId, leadId);

        apply(() -> lead.recordFirstContact(OffsetDateTime.now(clock)));
        lead.recordActor(SecurityContext.require().userId(), false);
        Lead saved = leads.save(lead);

        audit.record(AuditAction.LEAD_CONTACTED, "Lead", saved.id(),
                Map.of("stage", "assigned"), Map.of("stage", saved.stage().code()), null);
        return saved;
    }

    @Transactional
    public Lead qualify(UUID leadId) {
        UUID tenantId = TenantContext.require();
        Lead lead = require(tenantId, leadId);
        requireOwnerOrManager(lead);

        apply(lead::qualify);
        lead.recordActor(SecurityContext.require().userId(), false);
        Lead saved = leads.save(lead);

        audit.record(AuditAction.LEAD_QUALIFIED, "Lead", saved.id(),
                Map.of("stage", "contacted"), Map.of("stage", saved.stage().code()), null);
        return saved;
    }

    /** Doc 18: owner, team leader or branch manager; a reason is required. */
    @Transactional
    public Lead disqualify(UUID leadId, String reason) {
        UUID tenantId = TenantContext.require();
        Lead lead = require(tenantId, leadId);
        requireOwnerOrManager(lead);

        apply(() -> lead.disqualify(reason));
        lead.recordActor(SecurityContext.require().userId(), false);
        Lead saved = leads.save(lead);

        audit.record(AuditAction.LEAD_DISQUALIFIED, "Lead", saved.id(),
                Map.of("status", "active"), Map.of("status", saved.status().code()), reason);
        return saved;
    }

    /** Doc 18: reactivation is a manager or operations decision, with a reason. */
    @Transactional
    public Lead reactivate(UUID leadId, UUID newOwnerUserId, String reason) {
        authorization.requireAnyRole(Role.BRANCH_MANAGER, Role.OPERATIONS, Role.OWNER,
                Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();

        Lead lead = require(tenantId, leadId);
        var owner = requireAssignableOwner(tenantId, newOwnerUserId);

        apply(() -> lead.reactivate(owner.id(), reason));
        lead.recordActor(SecurityContext.require().userId(), false);
        Lead saved = leads.save(lead);

        audit.record(AuditAction.LEAD_REACTIVATED, "Lead", saved.id(),
                Map.of("status", "disqualified"),
                Map.of("status", saved.status().code(), "ownerUserId", owner.id().toString()),
                reason);
        return saved;
    }

    @Transactional
    public Lead setNextAction(UUID leadId, OffsetDateTime at) {
        UUID tenantId = TenantContext.require();
        Lead lead = require(tenantId, leadId);
        requireOwnerOrManager(lead);
        apply(() -> lead.setNextAction(at));
        lead.recordActor(SecurityContext.require().userId(), false);
        return leads.save(lead);
    }

    @Transactional
    public Lead captureInterest(UUID leadId, String interestJson) {
        UUID tenantId = TenantContext.require();
        Lead lead = require(tenantId, leadId);
        requireOwnerOrManager(lead);
        apply(() -> lead.captureInterest(interestJson));
        lead.recordActor(SecurityContext.require().userId(), false);
        return leads.save(lead);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Lead get(UUID leadId) {
        Lead lead = require(TenantContext.require(), leadId);
        authorization.requireRecordVisible(lead.ownerUserId(), lead.branchId(), "Lead");
        return lead;
    }

    /** Already narrowed to the caller's scope; there is no widening parameter. */
    @Transactional(readOnly = true)
    public Page<Lead> listVisibleToCaller(Pageable pageable) {
        UUID tenantId = TenantContext.require();
        var caller = SecurityContext.require();
        return switch (caller.role().branchScope()) {
            case TENANT_WIDE -> leads.findAllByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
            case OWN_BRANCH -> leads.findAllByTenantIdAndBranchIdOrderByCreatedAtDesc(
                    tenantId, caller.branchId(), pageable);
            case OWN_RECORDS -> leads.findAllByTenantIdAndOwnerUserIdOrderByCreatedAtDesc(
                    tenantId, caller.userId(), pageable);
        };
    }

    /** E2-S3: active leads whose next action has passed, narrowed to the caller's scope. */
    @Transactional(readOnly = true)
    public List<Lead> staleQueue() {
        UUID tenantId = TenantContext.require();
        var caller = SecurityContext.require();
        UUID branchFilter = caller.role().isTenantWide() ? null : caller.branchId();
        List<Lead> stale = leads.findStale(tenantId, OffsetDateTime.now(clock), branchFilter);
        return stale.stream()
                .filter(lead -> authorization.canSeeRecord(lead.ownerUserId(), lead.branchId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DuplicateMatch> findDuplicates(String phone) {
        return duplicates.findMatches(TenantContext.require(), phone, null);
    }

    // ------------------------------------------------------------------ internals

    Lead require(UUID tenantId, UUID leadId) {
        return leads.findByTenantIdAndId(tenantId, leadId)
                .orElseThrow(() -> ApiException.notFound("Lead"));
    }

    private void requireLoggedActivity(UUID tenantId, UUID leadId) {
        long logged = activities.countByTenantIdAndSubjectTypeAndSubjectId(
                tenantId, ActivitySubject.LEAD.code(), leadId);
        if (logged == 0) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Log the call, meeting or message before marking the lead contacted");
        }
    }

    private void requireOwnerOrManager(Lead lead) {
        authorization.requireRecordVisible(lead.ownerUserId(), lead.branchId(), "Lead");
    }

    private DirectoryUser requireAssignableOwner(UUID tenantId, UUID userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An owner must be named");
        }
        DirectoryUser owner = directory.findUser(tenantId, userId)
                .orElseThrow(() -> ApiException.notFound("User"));
        // Doc 18: the owner must be active. Assigning work to a deactivated account is how a
        // lead goes quiet without anyone noticing.
        if (!owner.active()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Cannot assign a lead to a deactivated user");
        }
        return owner;
    }

    private void requireBranchInTenant(UUID tenantId, UUID branchId) {
        if (branchId != null && !directory.branchExists(tenantId, branchId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The requested branch does not belong to this tenant");
        }
    }

    private void apply(Runnable change) {
        try {
            change.run();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
