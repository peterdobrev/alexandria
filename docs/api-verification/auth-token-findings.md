# Alexandria Security Audit — Authentication & JWT Token Handling

---

## Finding 1 — Password transmitted in JSON request body (Info/Low)

**Location:** `LoginRequest`, `RegisterRequest` DTOs; `POST /api/auth/login`, `POST /api/auth/register`

**Problem:** Passwords are sent as plain JSON fields in the request body. This is the standard practice for REST APIs and is acceptable when HTTPS is enforced end-to-end. However, the codebase shows no evidence of HTTPS enforcement (no `server.ssl.*` config, no redirect from HTTP to HTTPS, no HSTS header configuration in `SecurityConfig`). If deployed behind a TLS-terminating proxy, that responsibility shifts entirely to infrastructure with no application-layer guarantee.

**Correct behavior:** Acceptable pattern IF HTTPS is guaranteed at the infrastructure layer. The application should document this dependency. Consider adding `RequiresChannel` or HSTS headers via `SecurityConfig` to make the requirement explicit in code, not just ops convention.

**Severity:** Low (acceptable pattern, but HTTPS enforcement is implicit, not explicit)

---

## Finding 2 — JWT returned in response body, not `Authorization` header

**Location:** `AuthResponse.token`; `AuthController.register()`, `AuthController.login()`

**Problem:** The JWT is returned in the JSON response body. This is a common pattern, but it places the responsibility of secure storage entirely on the client. Clients storing the token in `localStorage` are vulnerable to XSS token theft. Clients storing it in an `HttpOnly` cookie get CSRF exposure instead (though CSRF is disabled here). The API sets `allowCredentials = true` in CORS, which has no effect for Authorization-header-based auth — this flag is only meaningful for cookie-based auth, making it a misleading configuration.

The `AuthResponse` DTO contains only a raw JWT string with no `token_type` field (should be `"Bearer"`), no `expires_in` hint, and no `scope`. This violates the implicit contract clients expect from an OAuth2-style token response and forces clients to hard-code assumptions about token type and lifetime.

**Correct behavior:**
- Add `token_type: "Bearer"` and `expires_in` (seconds) to `AuthResponse`.
- Document the storage recommendation for clients.
- If cookie-based delivery is adopted, set `HttpOnly`, `Secure`, `SameSite=Strict` and re-enable CSRF protection.
- Remove or justify `allowCredentials = true` if tokens are header-based.

**Severity:** Medium

---

## Finding 3 — JWT auto-issued on register without user consent signal

**Location:** `AuthService.register()` → returns `AuthResponse(token)` immediately

**Problem:** Registration automatically issues a JWT and logs the user in. This has several implications:

1. If email verification is ever added (a common future requirement), the architecture must change — auto-login before verification is a security regression.
2. There is no way for a client to register without immediately receiving a session credential. Some flows (admin-created accounts, invite flows) would need the register endpoint to NOT return a token.
3. The register endpoint returns `201 Created` while also issuing an active session token. `201` implies a resource was created; the implicit secondary action (session issuance) is not signalled in any response header (no `Location` for the new user resource, no indication this is also a login response).

**Correct behavior:** Either separate registration from login (register returns `201` with user resource location only, client must then call login), or make auto-login explicit in API documentation with a header like `X-Auto-Login: true`. If email verification is anticipated, this must be decoupled now.

**Severity:** Medium

---

## Finding 4 — `JwtAuthenticationFilter` returns empty 401 body; inconsistent with rest of API

**Location:** `JwtAuthenticationFilter` (on invalid token path); `SecurityConfig.exceptionHandling` authenticationEntryPoint

**Problem:** The filter calls `response.setStatus(401)` and returns, writing no response body. The `authenticationEntryPoint` calls `sendError(401)`. The rest of the API (via `GlobalExceptionHandler`) returns structured JSON `ErrorResponse` bodies for all other error conditions (400, 403, 404, 409, 500, etc.). This means:

- A client receiving a `401` from an expired/invalid token gets an empty body (or a default Tomcat/Jetty HTML error page if `sendError()` is used and an error page is configured), not a JSON `{"status":401,"error":"Unauthorized","message":"..."}`.
- A client receiving `401` from `BadCredentialsException` during login DOES get a JSON body (handled by `GlobalExceptionHandler`).
- The same HTTP status code (`401`) has two completely different response shapes depending on where in the stack it originates. This is a contract inconsistency that will cause client-side parsing failures.

**Correct behavior:** Implement a custom `AuthenticationEntryPoint` that writes a JSON `ErrorResponse` body with `Content-Type: application/json`. The filter's invalid-token path should do the same rather than calling `response.setStatus()` and returning silently.

```java
// Custom entry point example
response.setContentType(MediaType.APPLICATION_JSON_VALUE);
response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
objectMapper.writeValue(response.getOutputStream(),
    new ErrorResponse(401, "Unauthorized", "Authentication token is missing or invalid"));
```

**Severity:** High

---

## Finding 5 — `InvalidTokenException` not handled in `GlobalExceptionHandler`

**Location:** `GlobalExceptionHandler`; `JwtService.extractClaims()` wraps `JwtException` as `InvalidTokenException`

**Problem:** `InvalidTokenException` is not mapped in `GlobalExceptionHandler`. If this exception escapes the filter (e.g., if `JwtService` is called directly from a service or controller outside the filter context, or if the filter's catch block has a gap), it falls through to the generic `Exception` handler and returns `500 Internal Server Error`. An invalid token should always produce `401`, never `500`. A `500` on invalid token also leaks the existence of an exception type to monitoring/alerting, potentially including stack traces if error details are exposed.

**Correct behavior:** Add a `@ExceptionHandler(InvalidTokenException.class)` mapping in `GlobalExceptionHandler` returning `401`. Even if the expectation is that it never reaches the handler, defensive mapping is correct.

**Severity:** High

---

## Finding 6 — `UsernameNotFoundException` not handled in `GlobalExceptionHandler`

**Location:** `SecurityUtils.getCurrentUser()`; `GlobalExceptionHandler`

**Problem:** `SecurityUtils.getCurrentUser()` is called from multiple service methods (e.g., `CommentService.addComment()`, `RecommendationService`, etc.). If the user record has been deleted from the database after a valid token was issued (which is entirely possible — there is no token invalidation mechanism), `getCurrentUser()` throws `UsernameNotFoundException`. This is not handled in `GlobalExceptionHandler` and falls through to the generic handler, returning `500 Internal Server Error`.

The correct semantic is `401` (the token refers to a user that no longer exists — the session is no longer valid) or `404`, but definitely not `500`.

**Correct behavior:** Add `@ExceptionHandler(UsernameNotFoundException.class)` → `401` with message `"User account not found"`. Consider also invalidating tokens when a user is deleted (not possible without token blacklisting or short expiry, but the gap should be documented).

**Severity:** High

---

## Finding 7 — JWT claims: no issuer, no audience, no `jti`

**Location:** `JwtService.generateToken()`

**Problem:** The JWT is generated with only `subject` (email), `issuedAt`, and `expiration`. Missing:

- **`iss` (issuer):** Without an issuer claim, a JWT generated by a different application using the same secret (e.g., a staging environment, a microservice, or a future service added to the ecosystem) would be accepted as valid by this service.
- **`aud` (audience):** Without an audience claim, a token issued for one service can be replayed against another service that trusts the same secret.
- **`jti` (JWT ID):** Without a unique token ID, there is no mechanism to revoke individual tokens (e.g., on logout, on password change, on account compromise). Token blacklisting is architecturally impossible without `jti`.

The combination of no `iss`, no `aud`, and no `jti` means the token is maximally reusable across any system that happens to share the secret, and there is no revocation path.

**Correct behavior:**
```java
.issuer("alexandria")
.audience().add("alexandria-api").and()
.id(UUID.randomUUID().toString())
```
Validate `iss` and `aud` during `verifyWith()` setup. Store `jti` if revocation is needed.

**Severity:** High

---

## Finding 8 — JWT secret: no minimum strength enforcement beyond `WeakKeyException` at startup

**Location:** `JwtService` (`@Value`-injected secret); application startup

**Problem:** The `WeakKeyException` from the JJWT library is thrown at token generation time if the secret is too short for the chosen HMAC algorithm (HMAC-SHA256 requires ≥256 bits / 32 bytes). This is caught at runtime when the first token is generated, not at startup. If the startup path does not generate a token (e.g., no `@PostConstruct` validation), a weak or default secret could be deployed and only fail on first login attempt in production.

Additionally:
- There is no check for a default/example secret being used (e.g., the secret literally being `"secret"`, `"changeme"`, or matching a known-bad list).
- The secret is injected as a plain string from config. If the config value is logged anywhere (Spring Boot Actuator `/env` endpoint, debug-level config logging), the secret is exposed.
- There is no secret rotation mechanism.

**Correct behavior:**
1. Add a `@PostConstruct` method in `JwtService` that calls `generateToken()` with a dummy payload to force `WeakKeyException` at application startup rather than first login.
2. Add a check that the raw secret string meets minimum entropy (length ≥ 32 characters, not a well-known default value).
3. Exclude the JWT secret property from Actuator `/env` exposure.

**Severity:** High

---

## Finding 9 — Token expiration: validated, but no refresh mechanism; no explicit handling of `ExpiredJwtException`

**Location:** `JwtService.extractClaims()`; filter chain

**Problem:** Expiration IS validated by JJWT's `verifyWith()` chain — an expired token throws `ExpiredJwtException` (a subclass of `JwtException`), which is caught and wrapped as `InvalidTokenException`. So expiration is enforced.

However:
- There is no token refresh endpoint. Once a token expires, the only recourse is to call `/api/auth/login` again with credentials. For long-lived sessions (or aggressive expiry), this degrades UX and forces credential re-entry.
- `ExpiredJwtException` and a forged/malformed token are treated identically — both become `InvalidTokenException`. The client cannot distinguish "your token expired, please re-authenticate" from "your token is invalid, something is wrong." A well-behaved API would return different messages (both are `401`, but the `message` field should differ).
- No check exists for tokens issued in the future (`nbf` / `iat` in the future) — JJWT does not validate `nbf` by default unless explicitly configured.

**Correct behavior:**
- Add a `/api/auth/refresh` endpoint accepting a valid (not yet expired) token and returning a new one.
- Differentiate `ExpiredJwtException` from other `JwtException` subtypes in the catch block to produce distinct error messages.
- Consider validating `iat` is not in the future.

**Severity:** Medium

---

## Finding 10 — Login returns `200 OK`; semantic correctness

**Location:** `AuthController.login()` → `ResponseEntity.ok(...)`

**Problem:** `200 OK` for login is the established convention and is not wrong per se. Some APIs use `200` with the token in the body; others argue `201 Created` (creating a session resource). The current implementation is consistent with common REST auth patterns. However, there is no `Location` header pointing to a session resource, and the response shape is minimal.

The `401` for wrong credentials (via `BadCredentialsException` → `GlobalExceptionHandler`) is correct.

**Correct behavior:** No change required for the status code. Consider adding a `Location: /api/users/me` header to the login response as a convenience pointer to the authenticated user resource.

**Severity:** Info

---

## Finding 11 — Register returns `201 Created` while simultaneously issuing a session token

**Location:** `AuthController.register()` → `ResponseEntity.status(CREATED).body(...)`

**Problem:** `201` is semantically correct for resource creation. However, when the body of a `201` response contains an active session JWT (effectively also performing login), clients need to understand they are receiving both a user creation confirmation AND a session credential in one response. There is no `Location` header pointing to the newly created user resource (`/api/users/{newUserId}`), which is required by RFC 7231 for `201` responses.

**Correct behavior:** Add `Location` header pointing to the new user resource:
```java
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
    .replacePath("/api/users/{id}")
    .buildAndExpand(createdUser.id())
    .toUri();
return ResponseEntity.created(location).body(authResponse);
```

**Severity:** Low

---

## Finding 12 — Bearer prefix check is case-sensitive

**Location:** `JwtAuthenticationFilter`; `BEARER_PREFIX = "Bearer "`

**Problem:** The filter checks `header.startsWith("Bearer ")` (exact case). The HTTP `Authorization` header value is case-insensitive per RFC 6750 — `bearer TOKEN`, `BEARER TOKEN`, and `Bearer TOKEN` are all valid. A client sending `bearer <token>` (lowercase, which many OAuth2 libraries do by default) will have their token silently ignored, and they will receive a `401` with no explanation. This is a usability and compatibility issue, not a security vulnerability (it errs on the side of rejection), but it can cause integration failures.

**Correct behavior:**
```java
if (header == null || !header.toLowerCase().startsWith("bearer ")) {
    // no token present, continue chain unauthenticated
}
String token = header.substring(7).strip();
```

**Severity:** Medium

---

## Finding 13 — No rate limiting on `/api/auth/login` or `/api/auth/register`

**Location:** `SecurityConfig`; `AuthController`; no middleware visible in snapshot

**Problem:** There is no rate limiting, account lockout, or brute-force protection on the login or register endpoints. An attacker can:
- Enumerate valid email addresses by observing response times or error messages on login.
- Perform credential stuffing attacks against `/api/auth/login` at full network speed.
- Register unlimited accounts from a single IP, causing database growth and potential storage exhaustion.

The timing-safe `DUMMY_PASSWORD_HASH` check in `AuthService.login()` is present (good — prevents email enumeration via timing), but it is not sufficient protection against volumetric attacks.

**Correct behavior:**
- Add a Spring filter or Bucket4j/Resilience4j rate limiter on auth endpoints (e.g., 5 login attempts per IP per minute, 10 registrations per IP per hour).
- Consider adding account lockout after N failed login attempts (requires a failed-attempt counter per user in the database).
- Add CAPTCHA for registration.

**Severity:** High

---

## Finding 14 — Token leakage risk in logs

**Location:** `JwtAuthenticationFilter`; `JwtService`; any service calling these

**Problem:** The snapshot does not show `log.debug(token)` statements, which is positive. However, the risk is structural:
- Spring Boot's default `DEBUG`-level logging for `org.springframework.security` will log request details including headers. If `Authorization: Bearer <token>` headers are logged at DEBUG level (common in development configs that get deployed), tokens are written to log files.
- If `application.properties` has `logging.level.org.springframework.security=DEBUG` or `logging.level.root=DEBUG`, full request headers including the JWT are logged.
- There is no `@JsonIgnore` or masking on the `AuthResponse.token` field, which means if `AuthResponse` is ever accidentally logged as an object (e.g., `log.info("Auth response: {}", authResponse)`), the raw JWT is in the log.

**Correct behavior:**
- Ensure `logging.level.org.springframework.security` is not `DEBUG` in any deployed profile.
- Add `@ToString(exclude = "token")` (Lombok) to `AuthResponse` as a defensive measure.
- Add a log sanitization filter or use a structured logging library that can mask fields matching `Authorization` header patterns.

**Severity:** Medium

---

## Finding 15 — `Authorization: Bearer ` with blank token after prefix

**Location:** `JwtAuthenticationFilter`

**Problem:** The filter extracts `token = header.substring(7)` after the `"Bearer "` prefix check. If the header value is exactly `"Bearer "` (7 characters, nothing after), `token` is an empty string `""`. Whether `token.isBlank()` is checked determines whether an empty string is passed to `JwtService.extractClaims()`.

From the snapshot, the filter description says it "extracts Bearer token" and "on invalid token → 401." If there is no explicit blank check before calling `extractClaims("")`, JJWT will throw a `MalformedJwtException` (empty string is not a valid JWT), which becomes `InvalidTokenException`, which the filter catches and converts to 401. This path works but produces a 401 for a different reason than expected, and the path through the exception is more expensive than a simple blank check.

If the blank check IS present (`token.isBlank()`), the filter should treat it the same as no Authorization header (unauthenticated, continue the chain) rather than 401, because `"Bearer "` with no token could be a misconfigured client that should get an informative error.

**Correct behavior:**
```java
String token = header.substring(7).strip();
if (token.isBlank()) {
    filterChain.doFilter(request, response);
    return;
}
```
This lets the request proceed unauthenticated (endpoints requiring auth will then return 401 via the entry point), which is the correct semantic for "no token provided" vs "invalid token provided."

**Severity:** Low

---

## Finding 16 — `addComment()` relies on service-layer auth check, not `@PreAuthorize`

**Location:** `CommentController.addComment()`; `CommentService.addComment()`

**Problem:** The controller method for adding a comment has no `@PreAuthorize` and does not take `@AuthenticationPrincipal`. Authentication is enforced by `SecurityConfig`'s `anyRequest → authenticated` rule and by the service calling `securityUtils.getCurrentUser()`. This is layered security and is functional, but it means:
- The authentication enforcement is invisible at the controller layer — a code reviewer or future developer cannot see from the controller that auth is required.
- If `SecurityConfig` is ever changed to make `/api/documents/*/comments` POST public (matching the GET rule pattern), the auth check silently moves to the service layer, which would then throw `UsernameNotFoundException` (unmapped → 500) instead of a clean 401.
- The inconsistency with other write endpoints (which use `@PreAuthorize`) makes the security model harder to audit.

**Correct behavior:** Add `@PreAuthorize("isAuthenticated()")` to `CommentController.addComment()` for explicit, auditable auth enforcement at the controller boundary.

**Severity:** Medium

---

## Finding 17 — CORS `allowCredentials = true` with header-based JWT auth

**Location:** `SecurityConfig` CORS configuration

**Problem:** `allowCredentials = true` tells browsers to include cookies, HTTP authentication, and TLS client certificates in cross-origin requests. When using JWT in the `Authorization` header (not cookies), this flag has no effect on the token delivery mechanism. However, it widens the attack surface:
- With `allowCredentials = true`, the browser will include cookies in cross-origin requests. If the application ever sets any cookies (session, analytics, remember-me), they will be sent cross-origin to any allowed origin.
- `allowedHeaders = *` combined with `allowCredentials = true` is overly permissive — while browsers enforce CORS, it signals an unreviewed configuration.
- If `allowedOrigins` includes `*` or overly broad patterns, the combination with `allowCredentials = true` is a misconfiguration (browsers reject `Access-Control-Allow-Origin: *` with credentials, but a pattern like `*.example.com` could be exploited via subdomain takeover).

**Correct behavior:** Remove `allowCredentials = true` if the application exclusively uses `Authorization` header JWTs with no cookies. If cookies are used or planned, tighten `allowedOrigins` to explicit, verified origins only.

**Severity:** Medium

---

## Finding 18 — No token invalidation on logout / password change / account deletion

**Location:** `AuthService`; `JwtService`; user management flows

**Problem:** There is no logout endpoint and no token blacklist. A JWT remains valid until expiry regardless of:
- User logging out (no logout endpoint exists)
- User changing their password (old tokens still valid for full token lifetime)
- User account being deleted (tokens valid until expiry; compounded by Finding 6 — `UsernameNotFoundException` → 500)
- Admin revoking a user's access

This is a known JWT trade-off, but in combination with no `jti` claim (Finding 7), no refresh mechanism (Finding 9), and no explicit documentation of token lifetime, it means a compromised token cannot be invalidated without either a blacklist or waiting for expiry.

**Correct behavior:**
- Add a `POST /api/auth/logout` endpoint that adds the token's `jti` to a short-lived Redis/DB blacklist.
- On password change, increment a `tokenVersion` counter on the user entity and include it in the JWT; validate it on each request.
- Short token expiry (15–60 minutes) with a refresh token mechanism reduces the blast radius of token compromise.

**Severity:** High

---

## Summary Table

| # | Finding | Severity |
|---|---------|----------|
| 1 | HTTPS not enforced at application layer | Low |
| 2 | `AuthResponse` missing `token_type`, `expires_in`; `allowCredentials` misleading | Medium |
| 3 | Auto-login on register blocks future email verification | Medium |
| 4 | Filter/EntryPoint return empty body on 401; inconsistent with JSON API | High |
| 5 | `InvalidTokenException` unmapped → 500 | High |
| 6 | `UsernameNotFoundException` unmapped → 500 | High |
| 7 | JWT missing `iss`, `aud`, `jti` claims | High |
| 8 | Weak secret not caught at startup; no entropy check | High |
| 9 | No refresh token; `ExpiredJwtException` indistinguishable from invalid token | Medium |
| 10 | Login `200 OK` — acceptable, no `Location` header | Info |
| 11 | Register `201` has no `Location` header for new user resource | Low |
| 12 | Bearer prefix check is case-sensitive (RFC 6750 violation) | Medium |
| 13 | No rate limiting on auth endpoints | High |
| 14 | Token leakage via debug logging / `toString` | Medium |
| 15 | Blank token after `"Bearer "` prefix not explicitly guarded | Low |
| 16 | `addComment()` auth enforcement invisible at controller layer | Medium |
| 17 | CORS `allowCredentials = true` unnecessary for header-based auth | Medium |
| 18 | No token invalidation on logout/password change/deletion | High |
