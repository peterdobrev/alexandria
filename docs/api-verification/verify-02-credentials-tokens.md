# Adversarial Verification — 02-credentials-tokens.md

**Verified:** 2026-06-14  
**Reviewer role:** Adversarial — refute where the code is already safe, confirm where it is not  
**Source files read:** All 13 files listed in the original audit, plus `UserDetailsServiceImpl.java`, `UserService.java`, and `application.properties`

---

## F-01 — HIGH: JWT returned in response body

**Verdict: CONFIRMED**

`AuthResponse` (line 5) is a record with a `token` field that is serialized to JSON by Jackson with no `@JsonIgnore` or other suppression annotation.

```java
// AuthResponse.java:5
public record AuthResponse(UUID userId, String token, String tokenType, long expiresIn) {
```

Both `AuthController.register` (line 32) and `AuthController.login` (line 37) return this record as the HTTP response body. The token is therefore placed in the response body verbatim.

Checked for mitigations that might refute the finding:
- No `@JsonIgnore` on `token` field.
- No `HttpOnly` cookie path in `AuthController` or `SecurityConfig`.
- `toString()` is overridden to emit `token=***`, which is good for log safety but has no effect on JSON serialization.

The finding is factually correct. The token is in the body. The audit's severity framing ("not wrong per se for an API") is fair — the code is workable for non-browser clients — but the finding stands: no `HttpOnly` cookie alternative exists and no decision is documented.

---

## F-02 — MEDIUM: `expiresIn` exposed in every auth response

**Verdict: CONFIRMED**

`AuthResponse.of` always divides `expirationMillis / 1000` into the `expiresIn` field, which is serialized to JSON. No `@JsonIgnore` is present. The value comes directly from `jwt.expiration` (default 1 800 000 ms = 1800 s = 30 minutes).

```java
// AuthResponse.java:7-8
public static AuthResponse of(UUID userId, String token, long expirationMillis) {
    return new AuthResponse(userId, token, "Bearer", expirationMillis / 1000);
}
```

The field is always populated and always sent. The finding is factually correct.

---

## F-03 — MEDIUM: Raw email logged in `UserDetailsServiceImpl` and `UserService`

**Verdict: CONFIRMED — and the audit UNDERSTATES the scope**

The two locations the audit identifies are real:

```java
// UserDetailsServiceImpl.java:24
log.debug("Loading user by username: {}", username);   // raw email at DEBUG

// UserDetailsServiceImpl.java:38
log.warn("User not found for username: {}", username); // raw email at WARN

// UserService.java:39
log.info("Password changed for user: {}", user.getEmail()); // raw email at INFO
```

However, the audit's non-findings section claims `AuthService` is the only place that consistently uses `emailHash()`. Two additional logging sites were missed:

```java
// JwtService.java:55
log.debug("Generating JWT token for user: {}", user.getEmail()); // raw email at DEBUG

// JwtAuthenticationFilter.java:47
log.debug("Authenticated user: {}", email); // raw email at DEBUG
```

Both emit raw emails at `DEBUG` level. If `DEBUG` logging is enabled (e.g., during development or in an environment where `logging.level.com.alexandria=DEBUG` is set), these lines produce the same PII exposure. The audit should have flagged `JwtService` and `JwtAuthenticationFilter` as additional instances — they were not mentioned anywhere in the report.

The core finding is confirmed; the audit scope for F-03 is incomplete.

---

## F-04 — MEDIUM: `UsernameNotFoundException` message embeds raw email

**Verdict: CONFIRMED — with a correction on which code paths are affected**

The exception message construction is real:

```java
// UserDetailsServiceImpl.java:39
return new UsernameNotFoundException("User not found: " + username);
```

**Code path 1 — the filter (correctly identified):**
`JwtAuthenticationFilter.setAuthentication()` calls `userDetailsService.loadUserByUsername(email)`. If the user is absent, `UsernameNotFoundException` is thrown with the raw email in the message. The filter catches it at line 49 and logs it:

```java
// JwtAuthenticationFilter.java:50
log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
```

`e.getMessage()` here is `"User not found: alice@example.com"`. The raw email enters the log via this path. This is correctly identified.

**Code path 2 — `GlobalExceptionHandler` (partially mis-stated):**
The audit says the `GlobalExceptionHandler` "sanitizes the client response" but "the log line is unprotected." The `GlobalExceptionHandler` at line 208 does log `ex.getMessage()`:

```java
log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
```

However, the `GlobalExceptionHandler` is a Spring MVC exception resolver; it does NOT handle exceptions thrown inside a servlet `Filter`. Since `JwtAuthenticationFilter` already catches the exception at line 49 and calls `entryPoint.commence()` directly, the `GlobalExceptionHandler` is never invoked for this specific path. The audit's phrasing implies the `GlobalExceptionHandler` is the active sanitizer for filter-path exceptions, which is inaccurate.

The raw email does still reach logs via code path 1 (the filter), so the finding stands. The route through `GlobalExceptionHandler` only applies if `UsernameNotFoundException` is thrown elsewhere in the request lifecycle (outside the filter), in which case the `GlobalExceptionHandler` log at line 208 would also log the raw email — making the overall exposure at least as bad as described.

---

## F-05 — MEDIUM: `APP_SHOW_SQL=true` would log `password_hash` bind parameters

**Verdict: CONFIRMED — but severity should be noted as operator-controlled**

```properties
# application.properties:12-13
spring.jpa.show-sql=${APP_SHOW_SQL:false}
spring.jpa.properties.hibernate.format_sql=${APP_SHOW_SQL:false}
```

The default is `false`, which is correct. The risk is real if an operator sets `APP_SHOW_SQL=true` in a production environment: Hibernate's built-in SQL logging does include bind parameters, and the `password_hash` column is written on registration (`AuthService.java:65`) and password change (`UserService.java:38`).

The finding is factually correct. The code does not contain any warning comment directing operators to keep this off in production, which is the specific gap the audit identifies.

---

## F-06 — MEDIUM: No token invalidation / logout mechanism

**Verdict: CONFIRMED**

A systematic search of the entire codebase for `logout`, `blacklist`, `tokenVersion`, and `jti` (as revocation identifiers) returned no results. The `JwtService` generates tokens that are self-contained and expire only after the `jwt.expiration` window (default 30 minutes). There is no `POST /api/auth/logout` endpoint. A stolen token cannot be revoked by the user.

The audit's description of the risk (password change does not revoke existing tokens, full expiry window available to attacker) is accurate.

---

## F-07 — LOW: `POST /register` issues a JWT before email verification

**Verdict: CONFIRMED**

```java
// AuthService.java:72
return AuthResponse.of(user.getId(), jwtService.generateToken(user), jwtExpiration);
```

Registration immediately issues a valid JWT. There is no `emailVerified` field on `User`, no verification token table, and no check in `AuthService.login` gating on verification. This is a design choice, not a code defect, but the finding accurately describes the absence of email ownership verification.

---

## F-08 — LOW: `UpdateUserRequest.password` lacks `@JsonProperty(WRITE_ONLY)` guard

**Verdict: PARTIALLY-CORRECT — the annotation is genuinely absent, but the actual exposure risk is lower than stated**

The annotation is absent — confirmed:

```java
// UpdateUserRequest.java:12-14
@NullOrNotBlank(message = "Password must not be blank")
@Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
String password
```

No `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)` is present anywhere in the codebase.

However, the audit's risk statement needs qualification. The audit says "if a mistake in a future controller echoed the request body back, the plaintext password would be serialized to the response." In the current code, `UserController.updateUser` explicitly returns `ResponseEntity<UserSummary>`, not `ResponseEntity<UpdateUserRequest>`:

```java
// UserController.java:40-43
public ResponseEntity<UserSummary> updateUser(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateUserRequest request) {
    return ResponseEntity.ok(userService.update(id, request));
}
```

`UserService.update` returns `UserSummary` (id + displayName only). The `UpdateUserRequest` object is never returned or serialized to a response in the current code. The risk is theoretical — a future developer would have to return the wrong type — but it is not a present vulnerability.

The `@JsonProperty(WRITE_ONLY)` annotation is still a good defensive practice and should be added. The finding is valid as defence-in-depth advice but is overstated as a current vulnerability. `LoginRequest` and `RegisterRequest` have the same absence, and neither is ever returned by a controller, making the same reasoning apply.

---

## F-09 — LOW: No `Cache-Control: no-store` on auth endpoints

**Verdict: CONFIRMED**

A search across all Java source files for `Cache-Control`, `no-store`, and `no-cache` returned no results. `SecurityConfig` sets HSTS, Referrer-Policy, and CSP headers (lines 86–92) but does not set `Cache-Control: no-store` on auth responses. Spring Boot's default JSON response headers do not include `Cache-Control: no-store`.

The absence is real. The risk (intermediate proxy or browser cache storing a token-containing response body) is plausible for any HTTP/1.1 client that does not send its own `Cache-Control: no-store` request header.

---

## F-10 — INFO: `UserResponse` DTO with `email` — mapped but unused

**Verdict: CONFIRMED — and the specific risk scenario is accurate**

`UserResponse` (line 6) contains `email`:

```java
public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {}
```

`UserMapper.toResponse()` builds a `UserResponse` with the user's email. No controller currently calls `toResponse()` — `UserController` uses `UserSummary` (id + displayName only) for all three of its endpoints, and `AuthController` uses `AuthResponse`.

The audit's latent-risk scenario is accurate: `GET /api/users/{id}` is `permitAll()` per `SecurityConfig` line 99. If a future developer changed `UserController.getUser` to call `toResponse()` instead of `toSummary()`, every user's email would be exposed to unauthenticated callers. The dead code is a latent trap.

---

## Non-Findings — Verification of Claimed Controls

| Claimed Control | Verdict | Evidence |
|---|---|---|
| Passwords not logged in `AuthService` | CONFIRMED | All `AuthService` log statements use `emailHash()`. No password value appears in any log statement. |
| JWT secret not hardcoded | CONFIRMED | `jwt.secret=${JWT_SECRET}` with no default in `application.properties` line 19. `JwtService` validates minimum 32-char length and rejects known weak values. |
| Wildcard CORS rejected | CONFIRMED | `SecurityConfig` constructor (line 38) throws `IllegalStateException` if `allowedOrigins` contains `"*"`. |
| Token extraction from header only | CONFIRMED | `JwtAuthenticationFilter.extractToken()` reads exclusively from `HttpHeaders.AUTHORIZATION`. No query-parameter path. |
| `AuthResponse.toString()` masks token | CONFIRMED | `AuthResponse` overrides `toString()` to emit `token=***`. Does not affect JSON serialization, only logging. |
| Timing oracle / user enumeration on login | CONFIRMED | `AuthService.login()` always runs `passwordEncoder.matches()` against `DUMMY_PASSWORD_HASH` when user is absent. |
| `BadCredentialsException` message sanitized for client | CONFIRMED | `GlobalExceptionHandler` returns `"Invalid credentials"` hard-coded. `JsonAuthenticationEntryPoint` returns `"Authentication required"`. |
| `passwordHash` not in `AuthResponse` | CONFIRMED | `AuthResponse` fields: `userId`, `token`, `tokenType`, `expiresIn`. No password-related field. |
| `passwordHash` not in `UserSummary` | CONFIRMED | `UserSummary` fields: `id`, `displayName`. Confirmed in source. |
| JWT claims do not embed roles | CONFIRMED | `JwtService.generateToken()` sets only `sub`, `iss`, `aud`, `iat`, `exp`, `jti` — no roles. |
| Token not accepted as body field | CONFIRMED | No DTO has a `token` field used for authentication input. |
| Password reset does not return/log plaintext | CONFIRMED | `UserService.update()` encodes the password before storing; `UserController` returns `UserSummary`. |

---

## Additional Finding Not in the Original Report

### AF-01 — LOW: Raw email logged at DEBUG in `JwtService` and `JwtAuthenticationFilter`

**Files:** `security/JwtService.java` (line 55), `security/JwtAuthenticationFilter.java` (line 47)

The original audit's non-findings section states that `AuthService` "correctly uses a short SHA-256 hash instead of the raw email for all its log lines" and implies the logging discipline is complete. This is incorrect.

Two additional log statements emit the raw email address:

```java
// JwtService.java:55
log.debug("Generating JWT token for user: {}", user.getEmail());

// JwtAuthenticationFilter.java:47
log.debug("Authenticated user: {}", email);
```

Both fire at `DEBUG` level. In development environments or any environment where `logging.level.com.alexandria=DEBUG` is configured, these lines produce PII exposure in logs — the same class of issue as F-03. These should either be suppressed or use the same `emailHash()` helper.

---

## Summary

| Finding | Verdict | Notes |
|---|---|---|
| F-01 HIGH — JWT in response body | CONFIRMED | No `@JsonIgnore`, no cookie alternative. |
| F-02 MEDIUM — `expiresIn` exposed | CONFIRMED | Always serialized, no suppression. |
| F-03 MEDIUM — Raw email in logs | CONFIRMED (scope understated) | Two additional DEBUG-level sites missed: `JwtService:55` and `JwtAuthenticationFilter:47`. |
| F-04 MEDIUM — Raw email in exception message | CONFIRMED (route mis-stated) | Filter catches exception before `GlobalExceptionHandler`; the raw email enters logs via the filter's own `log.warn`, not via the handler. |
| F-05 MEDIUM — SQL logging could expose `password_hash` | CONFIRMED | Default is `false`; no warning comment for operators. |
| F-06 MEDIUM — No token invalidation / logout | CONFIRMED | No logout endpoint, no blacklist, no token versioning. |
| F-07 LOW — Token issued without email verification | CONFIRMED | Design choice; no `emailVerified` guard. |
| F-08 LOW — No `@JsonProperty(WRITE_ONLY)` on password fields | PARTIALLY-CORRECT | Annotation genuinely absent; current exposure is theoretical only — no controller returns the DTO. |
| F-09 LOW — No `Cache-Control: no-store` on auth responses | CONFIRMED | No such header anywhere in the codebase. |
| F-10 INFO — `UserResponse` dead code with email | CONFIRMED | Accurate latent-risk description; `GET /api/users/{id}` is `permitAll`. |
| AF-01 LOW — Raw email at DEBUG in `JwtService` / `JwtAuthenticationFilter` | NEW FINDING | Not in original report; missed by non-findings section. |

**Confirmed:** 9  
**Partially-correct:** 1 (F-08)  
**Refuted:** 0  
**New findings added:** 1 (AF-01)
