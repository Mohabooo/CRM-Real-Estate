package com.rescrm.identity.api;

import com.rescrm.identity.api.IdentityDtos.MeResponse;
import com.rescrm.identity.api.IdentityDtos.SignInRequest;
import com.rescrm.identity.service.AuthenticationService;
import com.rescrm.identity.service.AuthenticationService.SignedIn;
import com.rescrm.identity.service.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in and out (doc 23's {@code /auth} resource).
 *
 * <p>Both endpoints are listed as public in {@code TenantAuthenticationFilter}, for different
 * reasons. Login obviously cannot require a session to create one. Logout is public so that
 * signing out always works: if it required a valid session, the one case where a person most
 * wants to clear their cookie — it has expired, or the account was deactivated — would answer
 * 401 and leave the cookie sitting in the browser.
 *
 * <p>The session token is returned as an {@code HttpOnly} cookie and never in the body. A
 * body would put the credential in reach of any script on the page, and in the browser's
 * network log, and quite possibly in a frontend state store that outlives it.
 *
 * <p>Password reset and refresh are the other two verbs doc 23 lists for this resource.
 * Refresh has nothing to do here — a server-side session slides its own expiry on use.
 * Reset needs an email channel, which arrives with notifications in Epic 10.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;
    private final SessionCookie cookie;

    public AuthController(AuthenticationService authentication, SessionCookie cookie) {
        this.authentication = authentication;
        this.cookie = cookie;
    }

    @PostMapping("/login")
    public ResponseEntity<MeResponse> login(@Valid @RequestBody SignInRequest request) {
        SignedIn signedIn = authentication.signIn(
                request.company(), request.email(), request.password());

        return ResponseEntity.ok()
                .header(cookie.header(), cookie.issue(signedIn.rawToken()))
                .body(MeResponse.from(signedIn.principal()));
    }

    /**
     * Always answers 204, whether or not the cookie named a live session.
     *
     * <p>A logout that reported "there was nothing to log out of" would tell an unauthenticated
     * caller whether a token was valid, and would give the frontend a failure to handle on the
     * one path that must never fail.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        cookie.readFrom(request).ifPresent(authentication::signOut);
        return ResponseEntity.noContent()
                .header(cookie.header(), cookie.clear())
                .build();
    }
}
