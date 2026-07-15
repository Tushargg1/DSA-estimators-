package com.dsatracker.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Issues and verifies bounded-lifetime HMAC JWTs without logging token material. */
@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final String audience;
    private final Duration lifetime;

    public TokenService(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.issuer:dsa-tracker}") String issuer,
            @Value("${auth.jwt.audience:dsa-tracker-web}") String audience,
            @Value("${auth.jwt.expiry:PT12H}") Duration lifetime) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("AUTH_JWT_SECRET must contain at least 32 bytes");
        }
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException("AUTH_JWT_EXPIRY must be greater than zero and at most 24 hours");
        }

        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        var audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, values -> values != null && values.contains(audience));
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        this.decoder = jwtDecoder;
        this.issuer = issuer;
        this.audience = audience;
        this.lifetime = lifetime;
    }

    public IssuedToken issue(Long userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(lifetime);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public Authentication authenticate(String token) {
        Jwt jwt = decoder.decode(token);
        Long.parseLong(jwt.getSubject());
        return new UsernamePasswordAuthenticationToken(
                jwt.getSubject(), token, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
