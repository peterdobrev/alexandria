package com.alexandria.security;

import com.alexandria.entity.User;
import com.alexandria.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class JwtService {

    private static final Set<String> KNOWN_WEAK_SECRETS =
            Set.of("secret", "changeme", "password", "changeit");

    private final SecretKey secretKey;
    private final long expiration;
    private final String issuer;
    private final String audience;

    public JwtService(String secret, long expiration, String issuer, String audience) {
        validateSecretEntropy(secret);
        try {
            this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        } catch (WeakKeyException ex) {
            throw new InvalidTokenException("JWT secret key is too weak", ex);
        }
        this.expiration = expiration;
        this.issuer = issuer;
        this.audience = audience;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("jwt.issuer must be configured");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("jwt.audience must be configured");
        }
        log.info("JwtService configured with issuer='{}' audience='{}'", issuer, audience);
    }

    public String generateToken(User user) {
        log.debug("Generating JWT token for user: {}", user.getEmail());
        return Jwts.builder()
                .subject(user.getEmail())
                .issuer(issuer)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public Claims extractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException("JWT token has expired", ex);
        } catch (JwtException ex) {
            throw new InvalidTokenException("JWT token is invalid or malformed", ex);
        }
    }

    private static void validateSecretEntropy(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 characters (256 bits) for HMAC-SHA256");
        }
        if (KNOWN_WEAK_SECRETS.contains(secret.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "jwt.secret is a well-known default value and must be replaced");
        }
    }
}
