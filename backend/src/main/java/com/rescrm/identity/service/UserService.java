package com.rescrm.identity.service;

import com.rescrm.identity.domain.User;
import com.rescrm.identity.repository.BranchRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Users within the caller's tenant.
 *
 * <p>Deactivation is the only way a user leaves. Doc 16 section 3 is explicit that it "never
 * orphans financial records": the row stays, so a deal keeps its agent of record and an
 * outbound commission keeps its payee.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final BranchRepository branches;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final AuthenticationService.TenantScopedAuthentication authentication;

    public UserService(UserRepository users, BranchRepository branches,
                       AuthorizationService authorization, AuditWriter audit,
                       AuthenticationService.TenantScopedAuthentication authentication) {
        this.users = users;
        this.branches = branches;
        this.authorization = authorization;
        this.audit = audit;
        this.authentication = authentication;
    }

    /**
     * The listing, already narrowed to what the caller may see.
     *
     * <p>The branch filter comes from the principal, so a branch manager cannot widen it by
     * asking. There is no parameter here for a client to manipulate.
     */
    @Transactional(readOnly = true)
    public List<User> listVisibleToCaller() {
        UUID tenantId = TenantContext.require();
        UUID branchFilter = authorization.branchFilterForCaller();
        return branchFilter == null
                ? users.findAllByTenantIdOrderByNameAsc(tenantId)
                : users.findAllByTenantIdAndBranchIdOrderByNameAsc(tenantId, branchFilter);
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        UUID tenantId = TenantContext.require();
        User user = users.findByTenantIdAndId(tenantId, userId)
                .orElseThrow(() -> ApiException.notFound("User"));
        authorization.requireBranchVisible(user.branchId(), "User");
        return user;
    }

    @Transactional
    public User deactivate(UUID userId, String reason) {
        authorization.requireIdentityAdministration();
        UUID tenantId = TenantContext.require();
        User user = users.findByTenantIdAndId(tenantId, userId)
                .orElseThrow(() -> ApiException.notFound("User"));

        if (user.id().equals(SecurityContext.require().userId())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "A user cannot deactivate their own account");
        }

        user.deactivate();
        user.recordActor(SecurityContext.require().userId(), false);
        User saved = users.save(user);

        // In the same transaction as the deactivation, not as an afterthought. E1-S3 asks
        // for a user to be deactivated; a deactivated user still signed in on three devices
        // has not been. This is the difference between a session and a self-contained token,
        // and the reason doc 28 section 6's first option was the one built.
        int endedSessions = authentication.revokeAllForUser(
                tenantId, saved.id(), "User deactivated");

        audit.record(AuditAction.USER_DEACTIVATED, "User", saved.id(),
                Map.of("active", true),
                Map.of("active", false, "sessionsEnded", endedSessions), reason);
        return saved;
    }

    @Transactional
    public User reactivate(UUID userId) {
        authorization.requireIdentityAdministration();
        UUID tenantId = TenantContext.require();
        User user = users.findByTenantIdAndId(tenantId, userId)
                .orElseThrow(() -> ApiException.notFound("User"));
        user.reactivate();
        user.recordActor(SecurityContext.require().userId(), false);
        User saved = users.save(user);
        audit.record(AuditAction.USER_REACTIVATED, "User", saved.id(),
                Map.of("active", false), Map.of("active", true), null);
        return saved;
    }

    /** Verifies a branch belongs to this tenant before anything is attached to it. */
    void requireBranchInTenant(UUID tenantId, UUID branchId) {
        if (branchId == null) {
            return;
        }
        if (!branches.existsByTenantIdAndId(tenantId, branchId)) {
            // The composite foreign key would refuse this too; failing here turns a driver
            // constraint error into an API error the caller can act on.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The requested branch does not belong to this tenant");
        }
    }
}
