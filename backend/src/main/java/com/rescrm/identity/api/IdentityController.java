package com.rescrm.identity.api;

import com.rescrm.identity.api.IdentityDtos.AcceptInvitationRequest;
import com.rescrm.identity.api.IdentityDtos.BranchResponse;
import com.rescrm.identity.api.IdentityDtos.CreateBranchRequest;
import com.rescrm.identity.api.IdentityDtos.DeactivateRequest;
import com.rescrm.identity.api.IdentityDtos.InvitationResponse;
import com.rescrm.identity.api.IdentityDtos.InviteRequest;
import com.rescrm.identity.api.IdentityDtos.IssuedInvitationResponse;
import com.rescrm.identity.api.IdentityDtos.MeResponse;
import com.rescrm.identity.api.IdentityDtos.RenameBranchRequest;
import com.rescrm.identity.api.IdentityDtos.TenantResponse;
import com.rescrm.identity.api.IdentityDtos.UserResponse;
import com.rescrm.identity.service.BranchService;
import com.rescrm.identity.service.InvitationService;
import com.rescrm.identity.service.TenantService;
import com.rescrm.identity.service.UserService;
import com.rescrm.platform.security.SecurityContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The identity endpoints from doc 23's resource map: {@code /tenants/current},
 * {@code /branches}, {@code /users} and {@code /users/{id}/deactivate}, plus {@code /me} and
 * the invitation flow Epic 1 needs.
 *
 * <p>No endpoint accepts a tenant id in a path, query or body. State changes are explicit
 * sub-resource actions rather than status PATCHes (doc 23, principle 2), which keeps
 * preconditions, permissions and the audit entry unambiguous.
 *
 * <p>Controllers translate HTTP and nothing else: the transaction boundary, the authorization
 * checks and the rules all live in the services, which the {@code controllers_delegate}
 * architecture rule enforces.
 */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    private final TenantService tenantService;
    private final BranchService branchService;
    private final UserService userService;
    private final InvitationService invitationService;
    private final Clock clock;

    public IdentityController(TenantService tenantService, BranchService branchService,
                              UserService userService, InvitationService invitationService,
                              Clock clock) {
        this.tenantService = tenantService;
        this.branchService = branchService;
        this.userService = userService;
        this.invitationService = invitationService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- tenant

    @GetMapping("/tenants/current")
    public TenantResponse currentTenant() {
        return TenantResponse.from(tenantService.current());
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.from(SecurityContext.require());
    }

    // -------------------------------------------------------------- branches

    @GetMapping("/branches")
    public List<BranchResponse> listBranches() {
        return branchService.list().stream().map(BranchResponse::from).toList();
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse createBranch(@Valid @RequestBody CreateBranchRequest request) {
        return BranchResponse.from(branchService.create(request.name()));
    }

    @GetMapping("/branches/{id}")
    public BranchResponse getBranch(@PathVariable UUID id) {
        return BranchResponse.from(branchService.get(id));
    }

    @PostMapping("/branches/{id}/rename")
    public BranchResponse renameBranch(@PathVariable UUID id,
                                       @Valid @RequestBody RenameBranchRequest request) {
        return BranchResponse.from(branchService.rename(id, request.name()));
    }

    @PostMapping("/branches/{id}/deactivate")
    public BranchResponse deactivateBranch(@PathVariable UUID id,
                                           @RequestBody(required = false) DeactivateRequest request) {
        return BranchResponse.from(
                branchService.deactivate(id, request == null ? null : request.reason()));
    }

    // ----------------------------------------------------------------- users

    /** Already narrowed to the caller's branch scope; there is no widening parameter. */
    @GetMapping("/users")
    public List<UserResponse> listUsers() {
        return userService.listVisibleToCaller().stream().map(UserResponse::from).toList();
    }

    @GetMapping("/users/{id}")
    public UserResponse getUser(@PathVariable UUID id) {
        return UserResponse.from(userService.get(id));
    }

    @PostMapping("/users/{id}/deactivate")
    public UserResponse deactivateUser(@PathVariable UUID id,
                                        @RequestBody(required = false) DeactivateRequest request) {
        return UserResponse.from(
                userService.deactivate(id, request == null ? null : request.reason()));
    }

    @PostMapping("/users/{id}/reactivate")
    public UserResponse reactivateUser(@PathVariable UUID id) {
        return UserResponse.from(userService.reactivate(id));
    }

    // ----------------------------------------------------------- invitations

    @GetMapping("/invitations")
    public List<InvitationResponse> listInvitations() {
        return invitationService.list().stream()
                .map(invitation -> InvitationResponse.from(invitation, clock))
                .toList();
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedInvitationResponse invite(@Valid @RequestBody InviteRequest request) {
        InvitationService.IssuedInvitation issued =
                invitationService.invite(request.email(), request.role(), request.branchId());
        return new IssuedInvitationResponse(
                InvitationResponse.from(issued.invitation(), clock), issued.rawToken());
    }

    /**
     * The one endpoint reachable without authentication, listed as public in
     * {@code TenantAuthenticationFilter}. The token is the credential.
     */
    @PostMapping("/invitations/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return UserResponse.from(
                invitationService.accept(request.token(), request.name(), request.password()));
    }
}
