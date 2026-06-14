Now I have a thorough picture of the codebase. Here is the implementation plan:

---

# Alexandria — Auth & JWT Security Implementation Plan

---

## 1. Executive Summary

The authentication stack has six high-severity gaps that together create a meaningfully exploitable surface. The most urgent are: (a) inconsistent 401 response shapes — the filter writes an empty body while the rest of the API returns structured JSON, breaking every client that parses error responses; (b) `InvalidTokenException` and `UsernameNotFoundException` are both unmapped in `GlobalExceptionHandler`, meaning a deleted-user token or a coding error in the filter path produces a 500 that leaks stack trace metadata; (c) `JwtService.generateToken()` emits tokens with no `iss`, `aud`, or `jti` claims, making tokens maximally reusable and permanently non-revocable; (d) the JWT secret strength is only validated at first token-generation time, not at startup; and (e) there is no rate limiting on the auth endpoints, leaving the login and register paths open to brute-force and account enumeration at line speed. The remaining findings range from RFC compliance oversights (case-sensitive Bearer prefix, missing `Location` header on 201) to missing quality-of-life fields in `AuthResponse`. Addressing them in dependency order will eliminate the 500-for-auth-failure category entirely and establish the foundations needed for future refresh and revocation work.

---

## 2. Prioritised Fix List

| Finding # | Title | Severity | Effort | Dependencies |
|-----------|-------|----------|--------|--------------|
| F5 | `InvalidTokenException` unmapped → 500 | High | S | none |
| F6 | `UsernameNotFoundException` unmapped → 500 | High | S | none |
| F4 | Filter/EntryPoint 401 with empty body | High | M | F5, F6 must be mapped first |
| F8 | Weak JWT secret not caught at startup | High | S | none |
| F7 | JWT missing `iss`, `aud`, `jti` claims | High | M | none (but coordinate with F9, F18) |
| F13 | No rate limiting on auth endpoints | High | L | none |
| F18 | No token invalidation on logout | High | L | F7 (`jti` required) |
| F12 | Bearer prefix case-sensitive (RFC 6750) | Medium | S | none |
| F2 | `AuthResponse` missing `token_type`, `expires_in`; `allowCredentials` misleading | Medium | S | none |
| F9 | No refresh token; `ExpiredJwtException` indistinguishable from invalid | Medium | L | F7 |
| F14 | Token leakage via `toString` / debug logging | Medium | S | none |
| F16 | `addComment()` auth invisible at controller layer | Medium | S | none |
| F17 | CORS `allowCredentials = true` unnecessary | Medium | S | none |
| F3 | Auto-login on register blocks future email verification | Medium | M | design decision needed |
| F11 | Register `201` missing `Location` header | Low | S | none |
| F1 | HTTPS not enforced at application layer | Low | S | none |
| F15 | Blank token after `"Bearer "` already guarded — partial improvement | Low | S | none |
| F10 | Login `200 OK` — semantic acceptable | Info | — | skip |

---

## 3. Implementation Steps

### F5 — `InvalidTokenException` unmapped in `GlobalExceptionHandler`

**File:** `exception/GlobalExceptionHandler.java`

Add a dedicated handler after the `BadCredentialsException` handler (around line 190):

```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                        HttpServletRequest request) {
    log.warn("Invalid JWT token on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
}
```

`InvalidTokenException` already has a single two-argument constructor `(String message, Throwable cause)`. No new classes needed.

**Test:** Unit test in `GlobalExceptionHandlerTest`: given `InvalidTokenException` is thrown, when the handler processes it, then the response status is 401 and the body message matches the exception message.

---

### F6 — `UsernameNotFoundException` unmapped in `GlobalExceptionHandler`

**File:** `exception/GlobalExceptionHandler.java`

Add after the F5 handler:

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "User account not found", request);
}
```

Import: `org.springframework.security.core.userdetails.UsernameNotFoundException`

The message is intentionally generic — the caller's message (which includes the email) must not be forwarded to the client.

**Test:** Unit test: given `UsernameNotFoundException` is thrown from `SecurityUtils.getCurrentUser()`, when it propagates to the handler, then status is 401 and message is `"User account not found"`.

---

### F4 — Filter and AuthenticationEntryPoint return empty body on 401

This fix has two sub-parts.

**Part A — Custom `AuthenticationEntryPoint`**

Create a new class `security/JsonAuthenticationEntryPoint.java`:

```java
package com.alexandria.security;

import com.alexandria.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;

@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorResponse body = new ErrorResponse(
                401, "Unauthorized", "Authentication required",
                Instant.now(), request.getRequestURI());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
```

**Part B — Wire into `SecurityConfig`**

`SecurityConfig` is at `security/SecurityConfig.java`. The `@Bean` in `AppConfig` or `SecurityConfig` that registers `JwtAuthenticationFilter` already exists. Add a `@Bean` for the entry point and inject `ObjectMapper` (Spring Boot auto-configures one):

In `SecurityConfig`:
- Add a constructor parameter `ObjectMapper objectMapper` (or inject via a new `@Bean`).
- Add a `@Bean` method:

```java
@Bean
public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return new JsonAuthenticationEntryPoint(objectMapper);
}
```

- Replace line 80 in `SecurityConfig.java`:

```java
// Before:
.exceptionHandling(ex -> ex.authenticationEntryPoint(
    (_, res, _) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))

// After:
.exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint))
```

Inject `JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint` via the `securityFilterChain` method parameter or constructor.

**Part C — Filter's invalid-token path**

In `JwtAuthenticationFilter.java`, lines 46-49, the catch block currently calls `response.setStatus(401)` and returns with no body. Replace with the same JSON-writing logic. The cleanest approach is to inject `JsonAuthenticationEntryPoint` into the filter and delegate to it:

Change the filter constructor to accept a third parameter:
```java
public JwtAuthenticationFilter(JwtService jwtService,
                                UserDetailsService userDetailsService,
                                JsonAuthenticationEntryPoint entryPoint)
```

Then the catch block becomes:
```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
    entryPoint.commence(request, response, new InsufficientAuthenticationException(e.getMessage(), e));
    return;
}
```

Update `SecurityConfig.jwtAuthenticationFilter()` bean method (line 42-45) to pass the entry point:
```java
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                        UserDetailsService userDetailsService,
                                                        JsonAuthenticationEntryPoint entryPoint) {
    return new JwtAuthenticationFilter(jwtService, userDetailsService, entryPoint);
}
```

**Test:** Integration test (or MockMvc slice): given a request with an invalid Bearer token, when the filter processes it, then the response is `application/json`, status 401, and the body deserializes to `ErrorResponse`.

---

### F8 — Weak JWT secret not caught at startup

**File:** `security/JwtService.java`

The constructor already throws `InvalidTokenException` when JJWT rejects a weak key (line 24-26). The problem is the `SecretKey` check happens correctly on construction from `AppConfig`, which runs at startup — so the `WeakKeyException` is actually caught at context initialization, not at first login. The remaining gap is: no check for known-trivial secrets (e.g., `"secret"`, `"changeme"`), and no minimum character-length guard before JJWT even sees it.

Add a private validation method called from the constructor before line 23:

```java
private static void validateSecretEntropy(String secret) {
    if (secret == null || secret.length() < 32) {
        throw new IllegalStateException(
            "jwt.secret must be at least 32 characters (256 bits) for HMAC-SHA256");
    }
    Set<String> knownWeakValues = Set.of("secret", "changeme", "password", "changeit");
    if (knownWeakValues.contains(secret.toLowerCase())) {
        throw new IllegalStateException(
            "jwt.secret is a well-known default value and must be replaced");
    }
}
```

Call `validateSecretEntropy(secret)` as the first line of the constructor.

Additionally, exclude `jwt.secret` from Actuator exposure. In `application.properties` (or the relevant profile properties file), add:
```
management.endpoint.env.keys-to-sanitize=jwt.secret,spring.datasource.password
```

**Test:** Unit test `JwtServiceTest`: given a secret shorter than 32 chars, when constructing `JwtService`, then `IllegalStateException` is thrown. Given `"changeme"` as secret, same result.

---

### F7 — JWT missing `iss`, `aud`, `jti` claims

**File:** `security/JwtService.java`

Extend `generateToken()` (line 30-38):

```java
public String generateToken(User user) {
    log.debug("Generating JWT token for user: {}", user.getEmail());
    return Jwts.builder()
            .subject(user.getEmail())
            .issuer("alexandria")
            .audience().add("alexandria-api").and()
            .id(UUID.randomUUID().toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiration))
            .signWith(secretKey)
            .compact();
}
```

Import `java.util.UUID`.

Extend `extractClaims()` to validate `iss` and `aud` during parsing. With JJWT 0.12.x the parser builder supports `requireIssuer` and `requireAudience`:

```java
public Claims extractClaims(String token) {
    try {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer("alexandria")
                .requireAudience("alexandria-api")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    } catch (JwtException ex) {
        throw new InvalidTokenException("JWT token is invalid or expired", ex);
    }
}
```

The `iss` and `aud` values should be externalized to `application.properties` as `jwt.issuer` and `jwt.audience` and injected into `JwtService` alongside `jwt.secret` and `jwt.expiration`. Update the `JwtService` constructor signature and `AppConfig.jwtService()` factory accordingly.

The `jti` value is included in the token but validation/blacklisting is deferred to F18 (logout implementation). The claim is included now so the infrastructure is ready.

**Test:** Unit tests in `JwtServiceTest`: given a token generated by a different issuer, when `extractClaims` is called, then `InvalidTokenException` is thrown. Given a valid token generated by this service, claims include `iss`, `aud`, and a non-null `jti`.

---

### F13 — No rate limiting on auth endpoints

This is the highest-effort finding. The recommended approach is Bucket4j via its Spring Boot starter, which integrates with the filter chain without requiring Redis (in-memory is sufficient for a single-instance deployment).

**New dependency in `pom.xml`:**
```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.x.y</version>
</dependency>
```

**New class:** `security/RateLimitingFilter.java` — a `OncePerRequestFilter` that applies per-IP limits:

- Login: 10 requests per minute per IP
- Register: 5 requests per 10 minutes per IP

The filter inspects `request.getRequestURI()` and applies a `ConcurrentHashMap<String, Bucket>` keyed on `request.getRemoteAddr()`. On rate-limit exhaustion, write a JSON `ErrorResponse` with status 429 and message `"Too many requests"`.

Register the filter in `SecurityConfig` before `JwtAuthenticationFilter`:
```java
.addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter.class)
```

Add a `@Bean` for `RateLimitingFilter` in `SecurityConfig` (or a new `RateLimitConfig`).

Externalize limits to `application.properties`:
```
app.rate-limit.login.requests=${APP_RATE_LIMIT_LOGIN_REQUESTS:10}
app.rate-limit.login.window-seconds=${APP_RATE_LIMIT_LOGIN_WINDOW_SECONDS:60}
app.rate-limit.register.requests=${APP_RATE_LIMIT_REGISTER_REQUESTS:5}
app.rate-limit.register.window-seconds=${APP_RATE_LIMIT_REGISTER_WINDOW_SECONDS:600}
```

**Test:** Unit test: given 11 successive login requests from the same IP, the 11th returns 429. Given requests from two different IPs, each gets a fresh bucket.

---

### F18 — No token invalidation on logout

Defer full implementation (requires `jti` from F7). The minimum viable step is:

1. Add `POST /api/auth/logout` to `AuthController` — it clears the SecurityContext. Without a blacklist this is client-side only, but it establishes the endpoint contract.
2. Document in code (comment on `AuthService`) that full server-side invalidation requires a `jti` blacklist backed by Redis or a DB table keyed on `(jti, expiry)`.

The `tokenVersion` on `User` entity approach (for password-change invalidation) requires a schema migration and is a separate task.

**Test:** Verify the logout endpoint returns 204 and that subsequent requests with the old token are still accepted (documenting the known limitation until blacklist is added).

---

### F12 — Bearer prefix case-sensitive

**File:** `security/JwtAuthenticationFilter.java`, method `extractToken()`, line 58.

Replace:
```java
// Before (line 58):
if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {

// After:
if (authHeader == null || !authHeader.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
```

The token extraction on line 61 uses the original `authHeader` index 7 which remains correct since `"bearer "` and `"Bearer "` are both 7 characters.

Add `import java.util.Locale;`

The constant `BEARER_PREFIX` can be removed or kept for the `substring` call — replace line 61:
```java
String token = authHeader.substring(7).strip();
```

`strip()` also handles the blank-token case (F15), making the existing `token.isBlank() ? null : token` check on line 62 correct as-is.

**Test:** Unit test `JwtAuthenticationFilterTest`: given `Authorization: bearer <valid-token>` (lowercase), the filter authenticates successfully. Given `Authorization: BEARER <valid-token>`, same result.

---

### F2 — `AuthResponse` missing `token_type` and `expires_in`; `allowCredentials` misleading

**File:** `dto/AuthResponse.java`

Expand the record:
```java
public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn
) {
    public static AuthResponse of(String token, long expirationMillis) {
        return new AuthResponse(token, "Bearer", expirationMillis / 1000);
    }
}
```

**File:** `service/AuthService.java`, lines 66 and 83.

Replace `new AuthResponse(jwtService.generateToken(user))` with `AuthResponse.of(jwtService.generateToken(user), jwtExpiration)`. This requires passing `jwtExpiration` into `AuthService`. Update `AuthService` constructor to accept `long jwtExpiration` and update `AppConfig.authService()` to pass `jwtExpiration`.

**File:** `security/SecurityConfig.java`, line 53.

Remove `config.setAllowCredentials(true)` since the application uses Authorization-header JWTs, not cookies. If cookie auth is added later this line can be restored with explicit justification.

**Test:** Deserialize the login and register responses and assert `tokenType` is `"Bearer"` and `expiresIn` is a positive number. Assert `allowCredentials` is absent (or false) in CORS preflight responses.

---

### F14 — Token leakage via `toString` / debug logging

**File:** `dto/AuthResponse.java`

Add Lombok `@ToString(exclude = "token")` to the record. Records have a default `toString()` that includes all components — Lombok can override it. Alternatively, manually override `toString()`:
```java
@Override
public String toString() {
    return "AuthResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
}
```

**File:** `application.properties` (or the production profile properties).

Confirm the following is absent or set to a non-DEBUG level:
```
logging.level.org.springframework.security=WARN
```

**Test:** Instantiate `AuthResponse`, call `toString()`, assert the result does not contain the token value.

---

### F16 — `addComment()` auth invisible at controller layer

**File:** `controller/CommentController.java`, line 44-49.

Add `@PreAuthorize("isAuthenticated()")` to the `addComment` method:

```java
@PreAuthorize("isAuthenticated()")
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    return commentService.addComment(documentId, request, securityUtils.getCurrentUser());
}
```

`@EnableMethodSecurity` is already present on `SecurityConfig` (line 7), so no further configuration is needed.

**Test:** MockMvc test: given an unauthenticated POST to `/api/documents/{id}/comments`, the response is 401 (from the entry point) rather than 500 from `UsernameNotFoundException`.

---

### F17 — CORS `allowCredentials = true` unnecessary

**File:** `security/SecurityConfig.java`, line 53.

This is handled as part of F2 above. Remove `config.setAllowCredentials(true)`.

---

### F3 — Auto-login on register blocks future email verification

This is a design decision, not a pure bug fix. The recommended path is to keep the current behavior but make the intent explicit:

**File:** `dto/AuthResponse.java` — already improved by F2 (client sees `token_type` and `expires_in`, knows it is a credential).

**File:** `controller/AuthController.java` — add a comment above `register()` stating the current contract: registration immediately issues a session token, and this must be decoupled before adding email verification.

**Deferred:** If email verification is ever planned, `AuthService.register()` must be split: persist the user, send a verification email, return 201 with no token, and add a `POST /api/auth/verify` endpoint that issues the token on email confirmation.

No code change required now beyond the documentation comment. This item tracks a future architectural obligation.

---

### F11 — Register `201` missing `Location` header

**File:** `controller/AuthController.java`

The `Location` header requires the new user's ID. Currently `AuthResponse` does not carry a user ID. The minimal approach is to extend `AuthResponse` to also carry the user ID, or return a separate user resource. Given the existing architecture:

Add `userId` to `AuthResponse` (extend the record from F2):
```java
public record AuthResponse(UUID userId, String token, String tokenType, long expiresIn) { ... }
```

Update `AuthService.register()` to include `user.getId()` in the response.

In `AuthController.register()`:
```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    AuthResponse authResponse = authService.register(request);
    URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/users/{id}")
            .buildAndExpand(authResponse.userId())
            .toUri();
    return ResponseEntity.created(location).body(authResponse);
}
```

Import `org.springframework.web.servlet.support.ServletUriComponentsBuilder` and `java.net.URI`.

**Test:** Assert that the `Location` header in a register response matches `/api/users/{newUserId}`.

---

### F1 — HTTPS not enforced at application layer

**File:** `security/SecurityConfig.java`

Add HSTS header to the `securityFilterChain` configuration:

```java
.headers(headers -> headers
    .httpStrictTransportSecurity(hsts -> hsts
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000)
    )
)
```

This makes the HTTPS dependency explicit in code rather than purely an ops convention. Channel redirection (`requiresChannel`) is only appropriate if the app itself terminates TLS; behind a proxy, HSTS is sufficient.

**Test:** Verify the `Strict-Transport-Security` header is present in responses.

---

### F9 — No refresh token; `ExpiredJwtException` indistinguishable

**File:** `security/JwtService.java`

In `extractClaims()`, differentiate `ExpiredJwtException` from other `JwtException` subtypes:

```java
} catch (ExpiredJwtException ex) {
    throw new InvalidTokenException("JWT token has expired", ex);
} catch (JwtException ex) {
    throw new InvalidTokenException("JWT token is invalid or malformed", ex);
}
```

Import `io.jsonwebtoken.ExpiredJwtException`.

The refresh endpoint (`POST /api/auth/refresh`) is a larger feature. Create a `RefreshRequest` record with a `token` field. The endpoint validates the token is not yet expired (or within a configurable grace window), extracts the subject, and issues a new token. This requires `JwtService` to expose the remaining validity of a token and is tracked as a separate feature task.

**Test:** Unit test: given an expired token, `extractClaims` throws `InvalidTokenException` with message containing "expired". Given a malformed token, the message contains "invalid or malformed".

---

## 4. Recommended Implementation Order

This sequence respects dependencies and delivers the most visible correctness improvements first:

1. **F5** — Map `InvalidTokenException` → 401 in `GlobalExceptionHandler`. Zero risk, one method added.
2. **F6** — Map `UsernameNotFoundException` → 401 in `GlobalExceptionHandler`. Zero risk, one method added.
3. **F4** — Create `JsonAuthenticationEntryPoint`, wire into `SecurityConfig`, update `JwtAuthenticationFilter`. F5 and F6 must be done first so that exceptions that escape the filter also produce JSON 401s rather than 500s.
4. **F8** — Add `validateSecretEntropy()` to `JwtService` constructor. Independent, zero-risk startup guard.
5. **F12** — Case-insensitive Bearer prefix in `JwtAuthenticationFilter.extractToken()`. One-line change, no dependencies.
6. **F14** — `@ToString` override on `AuthResponse`, confirm logging levels. No functional dependencies.
7. **F7** — Add `iss`, `aud`, `jti` claims to `generateToken()` and validate in `extractClaims()`. Externalize `jwt.issuer` and `jwt.audience` to properties. This is a breaking change for any existing tokens in test/dev environments — coordinate with team before deploying to shared environments.
8. **F2 + F17** — Extend `AuthResponse` with `tokenType` and `expiresIn`, pass `jwtExpiration` into `AuthService`, remove `allowCredentials`. Bundled together because both touch `AuthResponse`.
9. **F11** — Add `userId` to `AuthResponse` and `Location` header in `AuthController.register()`. Depends on F2's `AuthResponse` changes.
10. **F16** — Add `@PreAuthorize("isAuthenticated()")` to `CommentController.addComment()`. One annotation.
11. **F1** — Add HSTS header config to `SecurityConfig`. One-line addition.
12. **F9** — Differentiate `ExpiredJwtException` from other `JwtException` in `JwtService`. Add this before implementing the refresh endpoint.
13. **F13** — Rate limiting filter with Bucket4j. Highest effort; safe to defer until the above are complete.
14. **F18** — Logout endpoint and `jti` blacklist. Depends on F7 (`jti` in token) and F13 (rate limiter in place). Full server-side revocation is a multi-sprint effort.
15. **F3** — Add documentation comment in `AuthController.register()` marking the email-verification decoupling obligation. No code change; track as a future design task.

---

## 5. What to Skip / Accept As-Is

**F10 — Login returns `200 OK`**
`200` for a login response is an established convention used by the majority of REST auth APIs including Auth0, Okta, and AWS Cognito. The `201 Created` alternative would imply a session resource was created and stored server-side, which contradicts the stateless JWT model. No change needed.

**F15 — Blank token after `"Bearer "` prefix**
Already handled. `extractToken()` in `JwtAuthenticationFilter` at line 62 already does `token.isBlank() ? null : token`. A blank token is treated as absent, the filter continues the chain unauthenticated, and downstream security rules apply. The existing behavior is correct. The only improvement possible (a more informative message to the caller) is cosmetic and not worth the noise.
