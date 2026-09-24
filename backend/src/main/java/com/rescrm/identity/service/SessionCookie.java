package com.rescrm.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reads and writes the session cookie.
 *
 * <p>Every attribute here is load-bearing.
 *
 * <p>{@code HttpOnly} keeps the value out of {@code document.cookie}, so a cross-site
 * scripting bug anywhere in the frontend cannot read the credential. This is the main reason
 * the session lives in a cookie rather than in {@code localStorage} with an {@code
 * Authorization} header: the header approach requires JavaScript to hold the token, which
 * means any script that runs on the page holds it too.
 *
 * <p>{@code SameSite=Lax} is the first half of the cross-site request forgery defence: the
 * browser will not attach this cookie to a POST that another site initiated.
 * {@code OriginCheckFilter} is the second half, because SameSite is enforced by the browser
 * and a defence that only exists in the client is not a defence.
 *
 * <p>{@code Secure} is on by default and can be turned off for local HTTP development. It is
 * a property rather than an environment sniff so that switching it off is a visible decision
 * in a configuration file, not something that happens quietly because a hostname looked
 * local.
 *
 * <p>No {@code Max-Age}: a session cookie that dies with the browser is the right default,
 * and the server-side row is what actually decides how long the session lasts anyway.
 */
@Component
public class SessionCookie {

    public static final String NAME = "crm_session";

    private final boolean secure;
    private final String sameSite;

    public SessionCookie(
            @Value("${crm.security.session.cookie-secure:true}") boolean secure,
            @Value("${crm.security.session.cookie-same-site:Lax}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public Optional<String> readFrom(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** The {@code Set-Cookie} value that starts a session. */
    public String issue(String rawToken) {
        return base(rawToken).build().toString();
    }

    /** The {@code Set-Cookie} value that clears one, whatever its current state. */
    public String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    public String header() {
        return HttpHeaders.SET_COOKIE;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
    }
}
