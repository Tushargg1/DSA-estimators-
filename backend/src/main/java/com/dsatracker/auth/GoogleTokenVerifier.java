package com.dsatracker.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Verifies Google Identity Services ID credentials before local authentication. */
@Service
public class GoogleTokenVerifier {
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ALLOWED_ISSUERS = Set.of(
            "https://accounts.google.com", "accounts.google.com");
    private static final String INVALID_CREDENTIAL = "Google authentication failed";

    private final String clientId;
    private final JwtDecoder decoder;

    public GoogleTokenVerifier(@Value("${auth.google.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.decoder = this.clientId.isEmpty() ? null : NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    public GoogleIdentity verify(String credential) {
        if (decoder == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured");
        }
        if (credential == null || credential.isBlank()) {
            throw unauthorized();
        }

        try {
            Jwt jwt = decoder.decode(credential);
            return identity(jwt);
        } catch (JwtException | IllegalArgumentException ex) {
            throw unauthorized();
        }
    }

    private GoogleIdentity identity(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = trimToNull(jwt.getSubject());
        String email = trimToNull(jwt.getClaimAsString("email"));
        String name = trimToNull(jwt.getClaimAsString("name"));
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");

        if (!ALLOWED_ISSUERS.contains(issuer)
                || !jwt.getAudience().contains(clientId)
                || expiresAt == null || !expiresAt.isAfter(Instant.now())
                || subject == null || subject.length() > 255
                || !Boolean.TRUE.equals(emailVerified)
                || email == null || email.length() > 150
                || name == null || name.length() > 100) {
            throw unauthorized();
        }

        return new GoogleIdentity(email.toLowerCase(Locale.ROOT), name);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIAL);
    }

    public record GoogleIdentity(String email, String name) {
    }
}
