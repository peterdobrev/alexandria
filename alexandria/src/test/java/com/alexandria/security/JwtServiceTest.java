package com.alexandria.security;

import com.alexandria.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256";
    private static final long EXPIRATION_MS = 3_600_000L;

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION_MS);
        user = new User();
        user.setEmail("test@example.com");
    }

    @Test
    void generateToken_createsTokenWithCorrectEmailSubject() {
        String token = jwtService.generateToken(user);
        Claims claims = jwtService.extractClaims(token);
        assertThat(claims.getSubject()).isEqualTo("test@example.com");
    }

    @Test
    void extractClaims_expiredToken_throwsJwtException() {
        JwtService shortLived = new JwtService(SECRET, -1L);
        String expired = shortLived.generateToken(user);
        assertThatThrownBy(() -> jwtService.extractClaims(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void extractClaims_tamperedToken_throwsJwtException() {
        assertThatThrownBy(() -> jwtService.extractClaims("tampered.token.value"))
                .isInstanceOf(JwtException.class);
    }
}
