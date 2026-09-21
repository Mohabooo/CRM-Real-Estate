package com.rescrm.identity.api;

import com.rescrm.identity.domain.Branch;
import com.rescrm.identity.domain.Invitation;
import com.rescrm.identity.domain.Tenant;
import com.rescrm.identity.domain.User;
import com.rescrm.platform.security.AuthenticatedPrincipal;
import com.rescrm.platform.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request and response shapes for the identity endpoints.
 *
 * <p>Responses are built from entities explicitly rather than serialising them. Two fields
 * must never reach a client — {@code password_hash} and {@code token_hash} — and an explicit
 * mapping makes their absence a visible decision instead of something a future annotation
 * could undo.
 *
 * <p>No request carries a tenant id. The server takes it from the authenticated principal, so
 * there is nothing here for a client to manipulate (doc 23, section 1).
 */
public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record TenantResponse(UUID id, String name, String status,
                                 String defaultCommercialModel, String settings,
                                 OffsetDateTime createdAt) {

        public static TenantResponse from(Tenant tenant) {
            return new TenantResponse(tenant.id(), tenant.name(), tenant.status().code(),
                    tenant.defaultCommercialModel(), tenant.settings(), tenant.createdAt());
        }
    }

    public record BranchResponse(UUID id, String name, boolean active,
                                 OffsetDateTime createdAt) {

        public static BranchResponse from(Branch branch) {
            return new BranchResponse(branch.id(), branch.name(), branch.isActive(),
                    branch.createdAt());
        }
    }

    public record CreateBranchRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record RenameBranchRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record DeactivateRequest(@Size(max = 500) String reason) {
    }

    /** Never carries the password hash. */
    public record UserResponse(UUID id, String name, String email, String phone, Role role,
                               UUID branchId, boolean active, OffsetDateTime createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(user.id(), user.name(), user.email(), user.phone(),
                    user.role(), user.branchId(), user.isActive(), user.createdAt());
        }
    }

    public record MeResponse(UUID userId, UUID tenantId, Role role, UUID branchId,
                             boolean mayAdministerIdentity, String branchScope) {

        public static MeResponse from(AuthenticatedPrincipal principal) {
            return new MeResponse(principal.userId(), principal.tenantId(), principal.role(),
                    principal.branchId(), principal.role().mayAdministerIdentity(),
                    principal.role().branchScope().name());
        }
    }

    public record InviteRequest(@NotBlank @Email @Size(max = 320) String email,
                                @NotNull Role role,
                                UUID branchId) {
    }

    /** Never carries the token hash; {@code token} is present only on the issue response. */
    public record InvitationResponse(UUID id, String email, Role role, UUID branchId,
                                     String status, OffsetDateTime expiresAt,
                                     OffsetDateTime acceptedAt) {

        public static InvitationResponse from(Invitation invitation, Clock clock) {
            return new InvitationResponse(invitation.id(), invitation.email(), invitation.role(),
                    invitation.branchId(), invitation.statusAt(clock.instant()).name(),
                    invitation.expiresAt(), invitation.acceptedAt());
        }
    }

    /**
     * The only response that ever contains the raw token.
     *
     * <p>Returned once, to the administrator who issued it, for delivery to the invitee. It is
     * not stored and cannot be retrieved again.
     */
    public record IssuedInvitationResponse(InvitationResponse invitation, String token) {
    }

    public record AcceptInvitationRequest(@NotBlank String token,
                                          @NotBlank @Size(max = 200) String name,
                                          @NotBlank @Size(min = 12, max = 200) String password) {
    }
}
