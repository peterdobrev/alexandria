# Credentials & Token Security Audit — Alexandria API

**Audited:** 2026-06-14  
**Scope:** Authentication and authorization layer — DTOs, controllers, services, filters, and configuration  
**Files reviewed:**

| File | Purpose |
|---|---|
| `controller/AuthController.java` | Register / login endpoints |
| `controller/UserController.java` | User profile / update endpoint |
| `dto/LoginRequest.java` | Login body |
| `dto/RegisterRequest.java` | Registration body |
| `dto/AuthResponse.java` | Token response DTO |
| `dto/user/UpdateUserRequest.java` | Password-change body |
| `dto/UserResponse.java` | User detail DTO (in mapper, unused in controllers) |
| `security/JwtAuthenticationFilter.java` | Bearer-token filter |
| `security/JwtService.java` | Token generation and validation |
| `security/SecurityConfig.java` | Spring Security configuration |
| `security/UserDetailsServiceImpl.java` | UserDetails loading |
| `security/JsonAuthenticationEntryPoint.java` | 401 JSON responses |
| `service/AuthService.java` | Register / login logic |
| `service/UserService.java` | Profile / password update logic |
| `config/AppConfig.java` | Bean wiring for JWT |
| `exception/GlobalExceptionHandler.java` | Exception-to-response mapping |
| `resources/application.properties` | Configuration |

---

## Summary

| # | Severity | Title |
|---|---|---|
| F-01 | HIGH | JWT returned in response body — stored in JS-accessible memory |
| F-02 | MEDIUM | `expiresIn` token lifetime exposed to clients in every auth response |
| F-03 | MEDIUM | Raw email logged in `UserDetailsServiceImpl` and `UserService` |
| F-04 | MEDIUM | `UsernameNotFoundException` message embeds raw email — logged with it |
| F-05 | MEDIUM | `APP_SHOW_SQL=true` would print `password_hash` bind parameter to logs |
| F-06 | MEDIUM | No token invalidation / logout mechanism — tokens are irrevocable |
| F-07 | LOW | `AuthResponse` returned by `POST /register` unnecessarily issues a token on signup |
| F-08 | LOW | `UpdateUserRequest` accepts `password` as a plain-text body field — no `@JsonProperty` write-only guard |
| F-09 | LOW | `Cache-Control` headers not explicitly set on auth endpoints — token responses may be cached |
| F-10 | INFO | `UserResponse` DTO exists with `email` field but is mapped and unused — latent exposure risk |

---

## Findings

---

### F-01 — HIGH: JWT returned in response body — stored in JS-accessible memory

**Files:** `dto/AuthResponse.java` (line 5), `controller/AuthController.java` (lines 27–33, 37), `service/AuthService.java` (lines 72, 89)

**What the code does:**

`AuthResponse` is a record that places the raw JWT string in a field named `token`:

```java
// dto/AuthResponse.java:5
public record AuthResponse(UUID userId, String token, String tokenType, long expiresIn) {
```

Both login and register return this record as a JSON response body:

```java
// AuthController.java:32-33
return ResponseEntity.created(location).body(authResponse);

// AuthController.java:37-38
return ResponseEntity.ok(authService.login(request));
```

The token is therefore stored wherever the HTTP client stores the response body — typically JavaScript `localStorage` or an in-memory variable.

**What it should do instead:**

The conventional alternative is to deliver the token in an `HttpOnly; Secure; SameSite=Strict` cookie so JavaScript code cannot read or steal it via XSS. The response body would then contain only non-sensitive metadata (e.g., `userId`, `tokenType`).

If the body approach is intentional (e.g., for a mobile client or SPA that manages its own secure storage), that design decision must be explicit in the documentation and clients must be instructed never to write the token to `localStorage`. It should instead be held in memory and sent via `Authorization: Bearer` on each request — which the existing filter (`JwtAuthenticationFilter`) already supports.

**The current design is not wrong per se for an API**, but the two options have very different XSS profiles. The audit flags this as HIGH because no decision is documented and the code provides no mitigation (e.g., cookie option, no `Secure` attribute enforcement, no warning to callers).

---

### F-02 — MEDIUM: `expiresIn` token lifetime exposed in every auth response

**Files:** `dto/AuthResponse.java` (lines 5, 8), `service/AuthService.java` (lines 72, 89)

**What the code does:**

```java
// dto/AuthResponse.java:7-8
public static AuthResponse of(UUID userId, String token, long expirationMillis) {
    return new AuthResponse(userId, token, "Bearer", expirationMillis / 1000);
}
```

The field `expiresIn` is always populated and serialized to JSON. Every login and register response reveals the exact lifetime of the token in seconds (e.g., `1800`).

**What it should do instead:**

`expiresIn` is included in the OAuth 2.0 token-endpoint response spec (RFC 6749 §5.1) and is legitimate for OAuth flows. However, for a proprietary API the value is unnecessary and tells an attacker exactly how long a stolen token remains valid, assisting replay-attack planning. If the API is not implementing OAuth, remove `expiresIn` from `AuthResponse`. If it is implementing a token endpoint that must comply with RFC 6749, keep it but document the rationale.

---

### F-03 — MEDIUM: Raw email address logged in `UserDetailsServiceImpl` and `UserService`

**Files:** `security/UserDetailsServiceImpl.java` (lines 24, 38–39), `service/UserService.java` (line 39)

**What the code does:**

```java
// UserDetailsServiceImpl.java:24
log.debug("Loading user by username: {}", username);

// UserDetailsServiceImpl.java:38-39
log.warn("User not found for username: {}", username);
return new UsernameNotFoundException("User not found: " + username);
```

```java
// UserService.java:39
log.info("Password changed for user: {}", user.getEmail());
```

The first two emit the raw email address in log entries at `DEBUG` and `WARN`. `AuthService` correctly uses a short SHA-256 hash instead of the raw email for all its log lines:

```java
// AuthService.java:67-68
log.warn("Registration attempt with already-used email: {}", emailHash(request.email()));
log.info("User registered successfully: {}", emailHash(request.email()));
```

The same discipline was not applied to `UserDetailsServiceImpl` or `UserService`. Log files are often aggregated into SIEM systems or third-party log management services that have different access-control postures than the application database, so storing plaintext email addresses in logs creates an unnecessary secondary exposure.

**What it should do instead:**

Replace raw email with the same `emailHash()` helper (or a shared utility) in every log statement that touches a user identifier:

```java
// UserDetailsServiceImpl
log.debug("Loading user by username: {}", emailHash(username));
log.warn("User not found for username: {}", emailHash(username));

// UserService
log.info("Password changed for user: {}", emailHash(user.getEmail()));
```

---

### F-04 — MEDIUM: `UsernameNotFoundException` embeds raw email in its message string

**Files:** `security/UserDetailsServiceImpl.java` (lines 38–39), `security/JwtAuthenticationFilter.java` (lines 49–51)

**What the code does:**

```java
// UserDetailsServiceImpl.java:38-40
return new UsernameNotFoundException("User not found: " + username);
```

The exception message contains the raw email address. This message is then:

1. Logged by `UserDetailsServiceImpl` itself (`log.warn("User not found for username: {}", username)`)
2. Caught in `JwtAuthenticationFilter` and its message is logged again:

```java
// JwtAuthenticationFilter.java:49-51
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
```

`e.getMessage()` here is `"User not found: alice@example.com"` — the raw email ends up in the application log a second time via a different code path.

The `GlobalExceptionHandler` does sanitize the client response (`"Authentication required"`), but the log line is unprotected.

**What it should do instead:**

Remove the email from the exception message, or hash it:

```java
return new UsernameNotFoundException("User not found");
```

All context needed for debugging is already emitted in the `log.warn` call immediately before throwing.

---

### F-05 — MEDIUM: `APP_SHOW_SQL=true` would log `password_hash` bind parameters to application logs

**Files:** `resources/application.properties` (lines 12–13), `service/AuthService.java` (lines 60–65), `service/UserService.java` (lines 37–41)

**What the code does:**

```properties
# application.properties:12-13
spring.jpa.show-sql=${APP_SHOW_SQL:false}
spring.jpa.properties.hibernate.format_sql=${APP_SHOW_SQL:false}
```

When `APP_SHOW_SQL=true` is set (the default is `false`, but this is an operator-level switch), Hibernate logs the full SQL including all bind parameters. Registration and password-change both write the `password_hash` column:

```java
// AuthService.java:60-61
String encodedPassword = passwordEncoder.encode(request.password());
User user = userMapper.toUser(request, encodedPassword);
...
userRepository.save(user);  // INSERT including password_hash

// UserService.java:38-39
user.setPasswordHash(passwordEncoder.encode(request.password()));
userRepository.save(user);  // UPDATE including password_hash
```

With `APP_SHOW_SQL=true`, the BCrypt hash appears in the log lines. While BCrypt hashes are not directly reversible, logging them is unnecessary and violates least-privilege log handling: log aggregation systems should not receive password-equivalent data.

**What it should do instead:**

Add a comment in `application.properties` clearly warning operators that `APP_SHOW_SQL=true` must never be enabled in production, and consider replacing the built-in Hibernate SQL logger with a filtered logging approach (e.g., `datasource-proxy`) that strips bind parameters for `password_hash` columns.

---

### F-06 — MEDIUM: No token invalidation / logout mechanism — tokens are irrevocable until expiry

**Files:** `controller/AuthController.java` (all), `security/JwtService.java` (all), `service/AuthService.java` (all)

**What the code does:**

There is no logout endpoint, no token blacklist, no token version/generation counter on the `User` entity, and no `POST /auth/logout`. A token issued at login remains fully valid until the `jwt.expiration` window closes (default 30 minutes per `jwt.expiration=${JWT_EXPIRATION:1800000}`).

This means:
- A user who suspects account compromise cannot invalidate their own sessions.
- An attacker who steals a token has the full expiry window to act, and the legitimate user has no recourse.
- Changing the user's password (via `PUT /api/users/{id}`) does not revoke existing tokens.

**What it should do instead:**

At minimum: add a `POST /api/auth/logout` that accepts the current token (from the `Authorization` header) and stores its `jti` claim in a short-lived deny-list (Redis or a DB table with TTL equal to the token expiry). The `JwtAuthenticationFilter` should check the deny-list on each request.

Longer term: store a `tokenVersion` integer on the `User` entity, embed it as a JWT claim, and increment it on password change. The filter rejects tokens whose version does not match the stored value.

---

### F-07 — LOW: `POST /register` issues a JWT immediately — issues token before email is verified

**Files:** `controller/AuthController.java` (lines 25–33), `service/AuthService.java` (lines 55–72)

**What the code does:**

```java
// AuthService.java:72
return AuthResponse.of(user.getId(), jwtService.generateToken(user), jwtExpiration);
```

Registration returns an `AuthResponse` containing a valid JWT. The user is authenticated the moment they register, with no email-verification step. This means:

- Any person who supplies an email address they do not own immediately gets a valid session token for that email identity.
- If a real owner of that email later tries to register, they get `409 Conflict`, but their account may already have been taken over.

This is a distinct issue from the token-in-body design: even if token delivery were changed to cookies, issuing a token on registration without verification is a security design flaw.

**What it should do instead:**

Return `201 Created` with only the `Location` header and no token body. Require the user to verify their email address before their first login can succeed (e.g., set an `emailVerified = false` flag on `User`, checked in `AuthService.login`).

---

### F-08 — LOW: `UpdateUserRequest` accepts `password` as a plain-text body field with no serialization guard

**Files:** `dto/user/UpdateUserRequest.java` (lines 12–14), `controller/UserController.java` (lines 39–42)

**What the code does:**

```java
// UpdateUserRequest.java:12-14
@NullOrNotBlank(message = "Password must not be blank")
@Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
String password
```

The `password` field is readable back as JSON (Jackson's default for records is bidirectional). If `UserService.update` ever returned an `UpdateUserRequest` or if a mistake in a future controller echoed the request body back, the plaintext password would be serialized to the response.

Additionally, there is no `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)` annotation on the field. This is the standard Jackson guard that makes a field deserializable (read from request) but never serializable (not written to response), regardless of how callers use the DTO.

**What it should do instead:**

```java
@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
@NullOrNotBlank(message = "Password must not be blank")
@Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
String password
```

Apply the same annotation to `LoginRequest.password` and `RegisterRequest.password` for defence in depth.

---

### F-09 — LOW: No `Cache-Control` headers on auth endpoints — token responses may be cached

**Files:** `controller/AuthController.java` (lines 25–38), `security/SecurityConfig.java` (lines 85–92)

**What the code does:**

`SecurityConfig` configures HSTS, Referrer-Policy, and a strict CSP, but does not set `Cache-Control: no-store` on auth responses. Spring Boot's default for JSON REST responses does not include `Cache-Control: no-store`.

RFC 6749 §10.3 (OAuth 2.0 Security Considerations) and common best practice require that token responses include:

```
Cache-Control: no-store
Pragma: no-cache
```

Without these headers, an intermediate proxy, CDN, or the browser's HTTP cache could store a response body that contains a JWT.

**What it should do instead:**

Add a response filter or annotate the `AuthController` methods to emit `Cache-Control: no-store` on all `/api/auth/**` responses:

```java
// In AuthController, or via a OncePerRequestFilter for /api/auth/**
response.setHeader("Cache-Control", "no-store");
response.setHeader("Pragma", "no-cache");
```

---

### F-10 — INFO: `UserResponse` DTO contains `email` field — mapped but unused, latent exposure risk

**Files:** `dto/UserResponse.java` (line 6), `mapper/UserMapper.java` (lines 24–26)

**What the code does:**

```java
// UserResponse.java:6
public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {}

// UserMapper.java:24-26
public UserResponse toResponse(User user) {
    return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
}
```

`toResponse()` builds a DTO that includes the user's email address. No controller currently calls `toResponse()` — `UserController` uses `UserSummary` (id + displayName only), and `AuthController` uses `AuthResponse`. The `UserResponse` DTO and mapper method exist but are dead code.

**Risk:** If a future developer wires `toResponse()` into a public endpoint (e.g., `GET /api/users/{id}`), it would expose the email of every user to unauthenticated callers (that endpoint is `permitAll()` per `SecurityConfig` line 99).

**What it should do instead:**

Either remove `UserResponse` and `toResponse()` if they serve no purpose, or annotate `email` with `@JsonIgnore` and add a comment explaining when it is appropriate to include the email (only for `/me`-style endpoints that return the authenticated user's own profile).

---

## Non-Findings (Controls Already in Place)

The following potential issues were reviewed and found to be adequately mitigated:

| Concern | Mitigation |
|---|---|
| Passwords logged in `AuthService` | `emailHash()` helper used consistently; no password value is ever in a log statement |
| JWT secret hardcoded | `jwt.secret=${JWT_SECRET}` with no default; `JwtService` validates minimum 32-char entropy and rejects known weak values |
| Wildcard CORS with credentials | `SecurityConfig` constructor rejects `"*"` in `APP_CORS_ALLOWED_ORIGINS` at startup |
| Token value in URL / query param | `JwtAuthenticationFilter.extractToken()` reads exclusively from the `Authorization` header; no `?token=` path exists |
| `AuthResponse.toString()` logging the token | Overridden to emit `token=***` |
| Timing oracle on login (user enumeration) | `AuthService.login()` always runs BCrypt against `DUMMY_PASSWORD_HASH` when the user does not exist |
| `BadCredentialsException` / `InvalidTokenException` reaching client | `GlobalExceptionHandler` and `JsonAuthenticationEntryPoint` both hard-code generic messages (`"Invalid credentials"`, `"Authentication required"`) |
| Hashed password in `AuthResponse` | `AuthResponse` contains only `userId`, `token`, `tokenType`, `expiresIn` — no `passwordHash` field |
| Hashed password in `UserSummary` | `UserSummary` contains only `id`, `displayName` — no sensitive fields |
| JWT claims exposing internal role names | JWT payload contains only `sub` (email), `iss`, `aud`, `iat`, `exp`, `jti` — roles are not embedded |
| Token accepted as body field | No DTO accepts a token in the request body; authentication is header-only |
| Password reset sending plaintext | No password-reset endpoint exists; `PUT /api/users/{id}` accepts a new password but never returns or logs the plaintext value |
