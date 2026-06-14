package com.alexandria.security;

import com.alexandria.entity.User;
import com.alexandria.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256";
    private static final long EXPIRATION_MS = 3_600_000L;
    private static final String ISSUER = "alexandria";
    private static final String AUDIENCE = "alexandria-api";

    private JwtService classUnderTest;
    private User user;

    @BeforeEach
    void setUp() {
        classUnderTest = new JwtService(SECRET, EXPIRATION_MS, ISSUER, AUDIENCE);
        user = new User();
        user.setEmail("test@example.com");
    }

    @Test
    void generateAndExtract_validToken_returnsClaims() {
        String token = classUnderTest.generateToken(user);
        Claims claims = classUnderTest.extractClaims(token);
        assertThat(claims.getSubject()).isEqualTo("test@example.com");
    }

    @Test
    void generateToken_createsTokenWithIssuerAudienceAndJti() {
        String token = classUnderTest.generateToken(user);
        Claims claims = classUnderTest.extractClaims(token);
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).contains(AUDIENCE);
        assertThat(claims.getId()).isNotNull();
    }

    @Test
    void generateToken_createsTokenWithUniqueJtiClaim() {
        String token1 = classUnderTest.generateToken(user);
        String token2 = classUnderTest.generateToken(user);
        Claims claims1 = classUnderTest.extractClaims(token1);
        Claims claims2 = classUnderTest.extractClaims(token2);
        assertThat(claims1.getId()).isNotBlank();
        assertThat(claims2.getId()).isNotBlank();
        assertThat(claims1.getId()).isNotEqualTo(claims2.getId());
    }

    @Test
    void extractClaims_expiredToken_throwsInvalidTokenException() {
        JwtService shortLived = new JwtService(SECRET, -1L, ISSUER, AUDIENCE);
        String expired = shortLived.generateToken(user);
        assertThatThrownBy(() -> classUnderTest.extractClaims(expired))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void extractClaims_tamperedToken_throwsInvalidTokenException() {
        assertThatThrownBy(() -> classUnderTest.extractClaims("tampered.token.value"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("invalid or malformed");
    }

    @Test
    void extractClaims_wrongIssuer_throwsInvalidTokenException() {
        JwtService differentIssuer = new JwtService(SECRET, EXPIRATION_MS, "other-issuer", AUDIENCE);
        String token = differentIssuer.generateToken(user);
        assertThatThrownBy(() -> classUnderTest.extractClaims(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void extractClaims_wrongAudience_throwsInvalidTokenException() {
        JwtService differentAudience = new JwtService(SECRET, EXPIRATION_MS, ISSUER, "other-audience");
        String token = differentAudience.generateToken(user);
        assertThatThrownBy(() -> classUnderTest.extractClaims(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void constructor_secretShorterThan32Chars_throwsIllegalStateException() {
        assertThatThrownBy(() -> new JwtService("too-short", EXPIRATION_MS, ISSUER, AUDIENCE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_knownWeakSecret_throwsIllegalStateException() {
        assertThatThrownBy(() -> new JwtService("changeme", EXPIRATION_MS, ISSUER, AUDIENCE))
                .isInstanceOf(IllegalStateException.class);
    }
}
