package com.alexandria.security;

import com.alexandria.entity.User;
import com.alexandria.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
public class JwtService {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtService(String secret, long expiration) {
        try {
            this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        } catch (WeakKeyException ex) {
            throw new InvalidTokenException("JWT secret key is too weak", ex);
        }
        this.expiration = expiration;
    }

    public String generateToken(User user) {
        log.debug("Generating JWT token for user: {}", user.getEmail());
        return Jwts.builder()
                .subject(user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public Claims extractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException ex) {
            throw new InvalidTokenException("JWT token is invalid or expired", ex);
        }
    }
}
