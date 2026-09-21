package com.rescrm.identity.service;

import com.rescrm.identity.domain.Invitation;
import com.rescrm.identity.domain.InvitationStatus;
import com.rescrm.identity.domain.User;
import com.rescrm.identity.repository.InvitationRepository;
import com.rescrm.identity.repository.UserRepository;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.PasswordHasher;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and accepts invitations (doc 20, E1-S2).
 *
 * <p>Acceptance is the one flow here that runs without a session: the person holding the
 * token has no account yet. The token therefore establishes the tenant, and nothing the
 * client sends is trusted to do so.
 */
@Service
public class InvitationService {

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final UserService userService;
    private final PasswordHasher passwordHasher;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final Clock clock;
    private final Duration validity;

    public InvitationService(InvitationRepository invitations, UserRepository users,
                             UserService userService, PasswordHasher passwordHasher,
                             AuthorizationService authorization, AuditWriter audit, Clock clock,
                             @Value("${crm.identity.invitation-validity:P7D}") Duration validity) {
        this.invitations = invitations;
        this.users = users;
        this.userService = userService;
        this.passwordHasher = passwordHasher;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
        this.validity = validity;
    }

    /** The invitation plus the one-time token, which exists only in this return value. */
    public record IssuedInvitation(Invitation invitation, String rawToken) {
    }

    @Transactional
    public IssuedInvitation invite(String email, Role role, UUID branchId) {
        authorization.requireIdentityAdministration();
        UUID tenantId = TenantContext.require();
        String normalizedEmail = normalize(email);

        userService.requireBranchInTenant(tenantId, branchId);

        if (users.existsByTenantIdAndEmail(tenantId, normalizedEmail)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "That email already has an account in this tenant");
        }
        if (invitations.findByTenantIdAndEmailAndAcceptedAtIsNull(tenantId, normalizedEmail)
                .isPresent()) {
            // The partial unique index enforces this too; checking first gives a usable error
            // instead of a constraint violation.
            throw new ApiException(ErrorCode.CONFLICT,
                    "An invitation for that email is already outstanding");
        }

        InvitationTokens.IssuedToken token = InvitationTokens.issue();
        Invitation invitation;
        try {
            invitation = Invitation.issue(tenantId, normalizedEmail, role, branchId,
                    token.tokenHash(), OffsetDateTime.now(clock).plus(validity));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        invitation.recordActor(SecurityContext.require().userId(), true);
        Invitation saved = invitations.save(invitation);

        audit.recordCreation(AuditAction.INVITATION_ISSUED, "Invitation", saved.id(),
                Map.of("email", saved.email(), "role", saved.role().name(),
                        "expiresAt", saved.expiresAt().toString()));

        return new IssuedInvitation(saved, token.rawToken());
    }

    @Transactional(readOnly = true)
    public List<Invitation> list() {
        authorization.requireIdentityAdministration();
        return invitations.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.require());
    }

    /**
     * Accepts an invitation and creates the user it was issued for.
     *
     * <p>Runs with no authenticated caller. The token is looked up by hash — the only
     * deliberately unscoped query in this module — and the tenant it names becomes the context
     * for everything that follows, so the new row cannot land anywhere else.
     *
     * <p>The response is identical for an unknown, expired and already-accepted token: all
     * three answer "not found". Distinguishing them would let someone with a list of guesses
     * learn which ones were real.
     */
    @Transactional
    public User accept(String rawToken, String name, String rawPassword) {
        Invitation invitation = invitations.findByTokenHash(InvitationTokens.hash(rawToken))
                .orElseThrow(() -> ApiException.notFound("Invitation"));

        return TenantContext.callAs(invitation.tenantId(), () -> {
            if (invitation.statusAt(clock.instant()) != InvitationStatus.PENDING) {
                throw ApiException.notFound("Invitation");
            }

            User user;
            try {
                user = User.create(invitation.tenantId(), invitation.email(),
                        passwordHasher.hash(rawPassword), name, null, invitation.role(),
                        invitation.branchId());
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
            }

            // No actor: the person does not exist as a user until this statement succeeds.
            // The unique constraint on (tenant_id, email) is what makes a concurrent second
            // acceptance fail rather than create a duplicate account.
            User savedUser = users.save(user);

            invitation.accept(savedUser.id(), OffsetDateTime.now(clock));
            invitations.save(invitation);

            audit.recordCreation(AuditAction.USER_CREATED, "User", savedUser.id(),
                    Map.of("email", savedUser.email(), "role", savedUser.role().name(),
                            "via", "invitation"));
            audit.record(AuditAction.INVITATION_ACCEPTED, "Invitation", invitation.id(),
                    Map.of("accepted", false),
                    Map.of("accepted", true, "userId", savedUser.id().toString()), null);

            return savedUser;
        });
    }

    private static String normalize(String email) {
        try {
            return User.normalizeEmail(email);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
