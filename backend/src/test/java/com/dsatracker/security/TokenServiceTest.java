package com.dsatracker.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {
    private final TokenService tokens = new TokenService(
            "test-only-signing-secret-that-is-longer-than-32-bytes",
            "test-issuer", "test-audience", Duration.ofHours(1));

    @Test
    void issuedTokenRestoresPrincipalAndHasBoundedExpiry() {
        Instant before = Instant.now();
        TokenService.IssuedToken issued = tokens.issue(42L);

        assertThat(tokens.authenticate(issued.value()).getName()).isEqualTo("42");
        assertThat(issued.expiresAt()).isAfter(before.plus(Duration.ofMinutes(59)));
        assertThat(issued.expiresAt()).isBefore(before.plus(Duration.ofMinutes(61)));
    }

    @Test
    void modifiedTokenIsRejected() {
        String token = tokens.issue(42L).value();
        assertThatThrownBy(() -> tokens.authenticate(token + "x"))
                .isInstanceOf(JwtException.class);
    }
}
