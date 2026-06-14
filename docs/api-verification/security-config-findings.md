Now I have a thorough picture of the entire codebase. Here is the complete security audit.

---

# Alexandria Spring Boot — Security Audit Report

**Date:** 2026-06-14  
**Scope:** Full source snapshot, all security-relevant layers

---

## F-01 — Authentication Entry Point Returns an Empty 401, Inconsistent with the API's Error Contract

**Severity: Medium**

**Location:** `SecurityConfig.java` line 80

```java
.exceptionHandling(ex -> ex.authenticationEntryPoint(
    (_, res, _) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
```

`sendError()` delegates to the servlet container's built-in error page mechanism. It does not go through `GlobalExceptionHandler`. The result is a plain-text or HTML `401` body, completely unlike every other error in the API (all of which return `ErrorResponse` JSON with `status`, `error`, `message`, `timestamp`, `path`). A client that receives a `401` on an expired token will get a non-JSON body and may crash its error-handling code.

**Attack scenario:** Not an attack, but inconsistent client-facing behaviour that could also leak server information through default container error pages (Tomcat's default error page includes the Tomcat version string when `server.error.include-message` is not locked down).

**Fix:** Replace with a proper `AuthenticationEntryPoint` that writes an `ErrorResponse` JSON body, exactly as the `AccessDeniedException` handler does.

---

## F-02 — No Custom AccessDeniedHandler; Spring's Default Uses sendError (No JSON Body for 403 from Filter Chain)

**Severity: Medium**

**Location:** `SecurityConfig.java` — no `accessDeniedHandler` configured

Spring Security's default `AccessDeniedHandler` is `AccessDeniedHandlerImpl`, which calls `sendError(403)`. Because `GlobalExceptionHandler` is a `@RestControllerAdvice`, it only intercepts exceptions that reach the DispatcherServlet. Exceptions thrown **inside the security filter chain** (i.e., before a request even reaches a controller) bypass the advice entirely.

There is one important nuance: `@EnableMethodSecurity` is active, so `AccessDeniedException` thrown by `@PreAuthorize` **does** propagate through the MVC layer and **is** caught by `GlobalExceptionHandler.handleAccessDenied()`. The inconsistency is therefore:

- `@PreAuthorize` failure on an authenticated user → JSON `403` via `GlobalExceptionHandler`. **Correct.**
- An anonymous request hitting a path that requires authentication → `401` via `sendError()`. **No JSON body.**
- A fully-authenticated request hitting a path that requires `ADMIN` but the default `AccessDeniedHandler` fires (which can happen for URL-level denials before the MVC layer) → plain `403` via `sendError()`.

**Fix:** Register a custom `AccessDeniedHandler` alongside the entry point that writes the same `ErrorResponse` JSON. Both should be wired in `exceptionHandling()`.

---

## F-03 — InvalidTokenException Is NOT Handled in GlobalExceptionHandler; Falls Through to 500

**Severity: High**

**Location:** `GlobalExceptionHandler.java` (no handler for `InvalidTokenException`), `JwtAuthenticationFilter.java` line 46

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}
```

The filter **does** catch `InvalidTokenException` itself and returns a `401`. However, `InvalidTokenException` can also be thrown by `JwtService` constructor at bean-creation time (`throw new InvalidTokenException("JWT secret key is too weak", ex)`). If that path is triggered, the exception bubbles out as an unchecked exception during application startup — the application fails to start, which is correct fail-fast behaviour.

The remaining problem is narrower: `InvalidTokenException` is a `RuntimeException` subclass that is **not** handled in `GlobalExceptionHandler`. If any non-filter code path were to throw it (e.g., calling `jwtService.extractClaims()` from a controller or service), it would fall through to the generic 500 handler, leaking an internal error for what is really a client fault. It should be mapped to `401`.

**Fix:** Add `@ExceptionHandler(InvalidTokenException.class)` → `401` in `GlobalExceptionHandler`.

---

## F-04 — UsernameNotFoundException From SecurityUtils.getCurrentUser() Falls Through to 500

**Severity: High**

**Location:** `SecurityUtils.java` lines 18–22, `GlobalExceptionHandler.java`

```java
public User getCurrentUser() {
    ...
    return userRepository.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
}
```

`SecurityUtils.getCurrentUser()` is called directly from controller methods (`DocumentController`, `CommentController`, `ReadingListController`). If a JWT's subject email no longer exists in the database (deleted account, data migration, etc.), this throws `UsernameNotFoundException`. That exception is not handled in `GlobalExceptionHandler` — it falls through to `handleGeneric()` and returns a `500` with "An unexpected error occurred", when the correct response is `401` (or `404` for the user).

Worse, the log line is `log.error(...)` with a full stack trace, which will alert on-call engineers for a routine invalid-token scenario.

**Fix:** Add `@ExceptionHandler(UsernameNotFoundException.class)` → `401 Unauthorized` (not `404`, because revealing "this email once existed" is a user enumeration risk) in `GlobalExceptionHandler`.

---

## F-05 — addComment Controller Has No Auth Guard at the HTTP Layer; Relies Entirely on Service Throwing

**Severity: Medium**

**Location:** `CommentController.java` line 45–49, `SecurityConfig.java`

```java
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(...) {
    return commentService.addComment(documentId, request, securityUtils.getCurrentUser());
}
```

`POST /api/documents/*/comments` is not mentioned in `SecurityConfig.authorizeHttpRequests`. The `anyRequest().authenticated()` catch-all would apply — but only after every more-specific rule. The specific rule `GET /api/documents/*/comments → permitAll` matches the same Ant pattern `"/api/documents/*/comments"` with `GET`, leaving `POST` to be handled by the catch-all `anyRequest().authenticated()`.

Spring Security evaluates rules in order. The `POST` will fall through to `anyRequest().authenticated()` and require auth correctly. **However:**

1. There is no `@PreAuthorize` or explicit rule for `POST /api/documents/*/comments`, making the intent invisible to code reviewers.
2. If someone adds a comment without auth, the JWT filter passes through (no token means no auth set), then `anyRequest().authenticated()` blocks it correctly with a `401 sendError` — but only after that filter. The service call `securityUtils.getCurrentUser()` would have thrown `UsernameNotFoundException` if reached, cascading into a 500 (see F-04), but since auth is checked before the controller, the 500 is not actually reachable today.

**Fix:** Add an explicit `requestMatchers(HttpMethod.POST, "/api/documents/*/comments").authenticated()` rule and add `@PreAuthorize("isAuthenticated()")` to the controller method to make intent explicit.

---

## F-06 — ReadingListService.addItem() Does Not Check Document Visibility

**Severity: Medium**

**Location:** `ReadingListService.java` lines 75–89

```java
public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request) {
    ...
    Document document = documentRepository.findById(request.documentId())
        .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
    // No visibility check here
```

An authenticated user can bookmark any document by UUID, including `PRIVATE` documents owned by other users. This is an access-control bypass: it leaks the existence of private documents (a 404 is returned by `DocumentService.get()` for private docs, but `addItem` returns 201), and it allows interaction logging (`interactionService.logBookmark()`) on documents the user should not be able to see.

**Fix:** Check `document.getVisibility()` in `addItem()`. If `PRIVATE` and the current user is not the author, throw `DocumentNotFoundException` (consistent with the masking strategy used elsewhere).

---

## F-07 — CORS `allowedMethods` Missing PATCH; Future PATCH Endpoints Will Silently Fail Cross-Origin

**Severity: Low**

**Location:** `SecurityConfig.java` line 51

```java
config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
```

`PATCH` is not in the allowed methods list. If a PATCH endpoint is added in the future, the pre-flight OPTIONS response will not include `PATCH` in `Access-Control-Allow-Methods`, and cross-origin PATCH requests from the frontend will fail with a CORS error — not a server error, meaning the server logs will show nothing wrong. This can be extremely difficult to diagnose.

**Fix:** Add `"PATCH"` to `allowedMethods`, or document explicitly in `SecurityConfig` that the allowed-methods list must be updated when new HTTP verbs are introduced.

---

## F-08 — CORS Configuration Only Covers `/api/**`; Swagger UI Paths Have No CORS Policy

**Severity: Low**

**Location:** `SecurityConfig.java` line 55

```java
source.registerCorsConfiguration("/api/**", config);
```

The Swagger UI paths (`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`) are not covered by any CORS configuration. A cross-origin request to `/v3/api-docs/openapi.json` (e.g., from a developer tool or CI pipeline running from a different origin) will not receive CORS headers. This is typically not exploitable but can cause issues in CI/CD API doc generation pipelines.

If the intent is for the API spec to be accessible cross-origin, register a separate (or the same) CORS configuration for `/v3/api-docs/**` and `/swagger-ui/**`.

---

## F-09 — CORS `allowedOrigins` Has No Runtime Guard Against Wildcard

**Severity: Medium**

**Location:** `SecurityConfig.java` line 50, `application.properties` line 23

```properties
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:4200}
```

```java
config.setAllowedOrigins(List.of(allowedOrigins));
config.setAllowCredentials(true);
```

The property accepts a single string. If an operator sets `APP_CORS_ALLOWED_ORIGINS=*`, the combination `allowedOrigins=["*"]` + `allowCredentials=true` is rejected by browsers per the CORS spec (browsers refuse to expose credentials to a wildcard origin). Spring's `CorsConfiguration` will also throw an `IllegalArgumentException` at runtime in recent versions when this combination is detected — but only when the CORS check is actually triggered by an incoming request, not at startup.

More critically, `allowedOrigins` accepts a **single origin** string. A misconfigured operator could set it to a value like `https://trusted.example.com, https://evil.example.com` (with a space), which would be treated as a literal origin string and would match nothing — silently breaking CORS for legitimate clients. Or they might set `https://*.example.com`, which is **not** a wildcard pattern in `setAllowedOrigins` (that requires `setAllowedOriginPatterns`), and would again silently break CORS.

**Fix:**
1. Add a startup validation bean (`@PostConstruct` or `ApplicationListener<ContextRefreshedEvent>`) that asserts `allowedOrigins` is not `*` when `allowCredentials=true`.
2. Consider switching to `setAllowedOriginPatterns` if subdomain wildcards are ever needed, and document the single-value constraint prominently.

---

## F-10 — JwtService Constructor Throws InvalidTokenException at Bean Creation; Application Does NOT Fail Gracefully

**Severity: Low / Info**

**Location:** `JwtService.java` lines 21–28, `AppConfig.java` line 35

```java
public JwtService(String secret, long expiration) {
    try {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    } catch (WeakKeyException ex) {
        throw new InvalidTokenException("JWT secret key is too weak", ex);
    }
```

This is the correct fail-fast behaviour. If `JWT_SECRET` is absent or too short (< 32 bytes for HS256), the Spring context will fail to start. However:

1. `InvalidTokenException` extends `RuntimeException` directly — it is not an `IllegalStateException` or `BeanCreationException`. When Spring wraps it, the outer exception is a Spring `BeanCreationException` with the `InvalidTokenException` as the cause. The startup log message will say "Error creating bean with name 'jwtService'" which is reasonably clear, but the root cause class name `InvalidTokenException` is misleading in this context (it sounds like a token parsing error, not a configuration error). Consider throwing `IllegalStateException` instead.
2. `jwt.expiration=21600000` is hardcoded in `application.properties` (6 hours in milliseconds) without an environment variable override. This is fine for a default, but long token lifetimes with no refresh mechanism and no token revocation mean a stolen token is valid for the full 6 hours with no remediation other than rotating the JWT secret.

---

## F-11 — No Token Revocation Mechanism; Stolen JWTs Are Valid Until Expiry

**Severity: High**

**Location:** `JwtService.java`, `AuthService.java`, `application.properties` (`jwt.expiration=21600000`)

The system issues opaque JWTs with a 6-hour expiry. There is no:
- Token blacklist or blocklist
- Refresh token mechanism
- Per-user token invalidation (e.g., on password change or logout)

**Attack scenario:** If a user's JWT is leaked (MITM, XSS, log exposure), the attacker has a 6-hour window to operate as that user with no way to terminate the session. Changing the user's password (via `PUT /api/users/{id}`) does not invalidate existing tokens.

**Fix (incremental options):**
1. Reduce `jwt.expiration` default to 15–30 minutes and add a refresh-token endpoint.
2. At minimum, invalidate all tokens on password change by embedding a per-user version counter in the JWT claims and incrementing it on password change.
3. Or maintain a small token blocklist for logout (only needs to survive until the token's natural expiry).

---

## F-12 — No Rate Limiting on Any Endpoint; Login Brute Force, Registration Spam, File Upload Flooding

**Severity: High**

**Location:** Entire application — no rate limiting anywhere

- `POST /api/auth/login`: `permitAll`, no lockout after N failures. The timing-safe BCrypt dummy hash (F-10 area) prevents user enumeration, but does not prevent password guessing. An attacker can attempt passwords at the rate the server can process BCrypt hashes (~10 req/s with work factor 10), targeting a known valid email indefinitely.
- `POST /api/auth/register`: `permitAll`, no limit. An attacker can create thousands of accounts (constrained only by unique email, but email providers have near-infinite aliasing). Each registration triggers a BCrypt encode operation, making registration a CPU exhaustion vector.
- `POST /api/documents` (file upload): requires auth but no per-user rate limit. A malicious authenticated user can flood the upload path and exhaust disk space or I/O.
- `POST /api/documents/*/interactions`: `authenticated`, but no limit prevents interaction-count inflation to skew recommendations.

**Fix:** Add `spring-boot-starter-data-redis` + Bucket4j or a similar token-bucket rate limiter as a servlet filter. At minimum, apply:
- `POST /api/auth/login`: 5 attempts per IP per minute, 20 per IP per hour.
- `POST /api/auth/register`: 3 registrations per IP per hour.
- File upload endpoints: per-user quota.

---

## F-13 — Swagger UI and OpenAPI Docs Are Publicly Accessible (`permitAll`) in All Environments

**Severity: Medium**

**Location:** `SecurityConfig.java` line 76

```java
.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
```

The OpenAPI spec (`/v3/api-docs/openapi.json`) exposes:
- All endpoint paths, methods, and parameter names
- All DTO schemas including field names and validation constraints
- Security scheme configuration (bearer JWT)
- The exact structure of every request and response, including error responses

For an internal or production API this is reconnaissance material. An attacker who has found the server can enumerate every endpoint without needing any credentials.

**Fix:** Use a Spring profile or a config property (`app.swagger.enabled=${APP_SWAGGER_ENABLED:false}`) to disable Swagger entirely in production, or move it behind authentication.

---

## F-14 — Session Policy Is STATELESS but Spring's Default Error Handling Can Create Sessions

**Severity: Low**

**Location:** `SecurityConfig.java` line 66

```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

`STATELESS` prevents Spring Security from creating or using sessions. However, Spring's `BasicErrorController` (the `/error` fallback invoked by `sendError()`) can create a session if the request goes through the error dispatching path, because `BasicErrorController` is invoked via a new `REQUEST_DISPATCHER` dispatch and session creation is managed per-dispatch. This is a known Spring Boot issue (SPR-15135 and related).

Since this application uses `sendError()` in the `AuthenticationEntryPoint` (F-01), the path through `BasicErrorController` is active and may create transient sessions. These sessions are harmless in themselves (they hold no data and expire immediately), but they are a sign that the STATELESS contract is being violated and could trigger session-fixation scanner false positives.

**Fix:** Resolve F-01 by removing the `sendError()` path entirely. Additionally, set `server.error.whitelabel.enabled=false` and `server.servlet.session.timeout=0` defensively.

---

## F-15 — Security Headers: Defaults Are Active, But No Content-Security-Policy

**Severity: Low**

**Location:** `SecurityConfig.java` — no explicit headers configuration

Spring Security's `HeadersConfigurer` is active by default with `@EnableWebSecurity`. The following headers are sent automatically:

- `X-Content-Type-Options: nosniff` — active
- `X-Frame-Options: DENY` — active
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` — active
- `Pragma: no-cache` — active
- `X-XSS-Protection: 0` (disabled in modern Spring Security, correct per current guidance) — active

**Not configured:**
- `Content-Security-Policy` — absent. For a pure REST API serving no HTML this is lower risk, but Swagger UI serves HTML and JavaScript. A CSP on Swagger UI paths would prevent XSS in the documentation UI from pivoting to the API.
- `Referrer-Policy` — absent. JWT tokens could appear in Referer headers if error pages link elsewhere.
- `Permissions-Policy` — absent (minor for an API).

**Fix:** Add a `headers` configuration block:
```java
.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
    .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
)
```
For Swagger UI paths, a more permissive CSP is needed (inline scripts). This may require per-path header configuration or a WebMvcConfigurer interceptor.

---

## F-16 — BCryptPasswordEncoder Strength Not Explicitly Configured or Documented

**Severity: Low / Info**

**Location:** `SecurityConfig.java` line 38

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

The no-arg constructor uses strength 10. The `DUMMY_PASSWORD_HASH` in `AuthService` hard-codes a strength-10 hash. If a future developer changes this to `new BCryptPasswordEncoder(12)`, the dummy hash would still be strength-10, causing `passwordEncoder.matches()` to return after a faster-than-expected BCrypt computation for the non-existent-user branch. This breaks the timing-safety guarantee.

The strength value is silently encoded in the `$2a$10$...` prefix of the dummy hash, but there is no assertion or test that validates the encoder's rounds match the dummy hash's rounds.

**Fix:** Declare the strength as a constant:
```java
private static final int BCRYPT_STRENGTH = 10;
```
Use it in both `SecurityConfig` (`new BCryptPasswordEncoder(BCRYPT_STRENGTH)`) and as documentation alongside `DUMMY_PASSWORD_HASH`. Add a unit test that asserts `encoder.upgradeEncoding(DUMMY_PASSWORD_HASH)` returns `false` (which fails if rounds differ).

---

## F-17 — JWT Filter Stops the Chain on Invalid Token but Returns an Empty Body (No JSON)

**Severity: Low**

**Location:** `JwtAuthenticationFilter.java` lines 46–50

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}
```

`response.setStatus(401)` sets the status code but writes no body. The response content-type is also not set. A client receives a `401` with zero-byte body and no `Content-Type` header. This is different from both the `sendError()` path (which produces an error page) and the `GlobalExceptionHandler` path (which produces JSON). Three different 401 shapes exist in the API.

**Fix:** Write the `ErrorResponse` JSON body directly in the filter using Jackson, or use `sendError()` consistently (and fix F-01 by customising the error page). The former is cleaner.

---

## F-18 — Content-Disposition Header: `originalFilename` Reflected Without Full RFC 5987 Encoding

**Severity: Low**

**Location:** `DocumentController.java` lines 117–119

```java
.header(HttpHeaders.CONTENT_DISPOSITION,
    "inline; filename=\"" + sanitize(sfr.originalFilename()) + "\"")
```

`sanitize()` strips `\`, `/`, `"`, control characters, and DEL. However, RFC 6266 / RFC 5987 require that filenames containing non-ASCII characters be encoded as `filename*=UTF-8''<percent-encoded>` to be interpreted correctly by browsers. A filename like `Üntersuchung.pdf` would pass through `sanitize()` unchanged (non-ASCII is allowed in `sanitize`), be placed inside the `filename=""` parameter, and be misinterpreted by some older user agents.

More importantly, a filename like `; type=application/javascript` (semicolon + parameter injection) would have the semicolon replaced with `_` by the storage-level `sanitizeFilename()` in `LocalFileStorageService` (which replaces non-allowlisted chars), so injection is mitigated at the storage layer. The controller-level `sanitize()` does not strip non-ASCII, however, meaning non-ASCII filenames work at the storage level but produce non-RFC-compliant `Content-Disposition` headers.

**Fix:** Use Spring's `ContentDisposition.builder("inline").filename(name, StandardCharsets.UTF_8).build().toString()` which handles RFC 5987 encoding automatically.

---

## F-19 — File Content-Type Is Reflected Verbatim from the Database into the Response

**Severity: Medium**

**Location:** `DocumentController.java` line 115

```java
.contentType(MediaType.parseMediaType(sfr.contentType()))
```

The `contentType` value was set at upload time from `MultipartFile.getContentType()`, which is provided by the HTTP client. Although `LocalFileStorageService.store()` validates that `contentType` is in the allowed set (`application/pdf`, `text/plain`, `text/html`, `application/epub+zip`), the stored value is later returned verbatim as the `Content-Type` of the download response.

`text/html` is in the allowed content types. Serving user-uploaded HTML files with `Content-Type: text/html` and `Content-Disposition: inline` causes the browser to **render** the HTML rather than downloading it, enabling stored XSS against users who click a document link. An attacker uploads an HTML file containing `<script>document.cookie</script>` as a document, shares the UUID (or makes the document public), and any user who clicks `GET /api/documents/{id}/file` executes the attacker's script in the context of the site's origin.

**Fix:** Either:
1. Remove `text/html` from `app.storage.allowed-content-types`, or
2. Force `Content-Disposition: attachment` (never `inline`) for all served files, preventing browser rendering, or
3. Serve files from a separate origin (e.g., a CDN or a different domain) so that even rendered HTML cannot access the main site's cookies.

This is the highest-impact finding in the storage/controller layer.

---

## F-20 — Private Document Files Are `permitAll` at the Security Layer; Defense-in-Depth Gap

**Severity: Low / Info**

**Location:** `SecurityConfig.java` line 69

```java
.requestMatchers(HttpMethod.GET, "/api/documents", "/api/documents/{id}", "/api/documents/{id}/file")
    .permitAll()
```

`GET /api/documents/{id}/file` is publicly accessible at the HTTP security layer. The visibility check is performed inside `DocumentService.streamFile()`, which correctly throws `DocumentNotFoundException` for private documents accessed by the wrong user. The defence-in-depth principle would suggest requiring authentication at the security layer for file streaming, since files are binary content and unauthenticated access to public files could be achieved with other patterns.

The current design is intentional (public documents can be downloaded without login), but it means there is a single layer of visibility enforcement. If a future refactoring inadvertently removes the service-level check, all private files would become publicly downloadable.

**Fix:** Document the intent explicitly in `SecurityConfig`. Consider adding a test that asserts a non-owner cannot stream a private document file via the full integration stack (not just the service unit test).

---

## F-21 — Security Config Pattern `/api/documents/*/comments` Uses Single-Wildcard; Could Match Unintended Paths

**Severity: Low**

**Location:** `SecurityConfig.java` line 70

```java
.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()
```

Spring Security's Ant-style `*` matches a single path segment (no slashes). The pattern `/api/documents/*/comments` therefore matches `/api/documents/abc123/comments` but does NOT match `/api/documents/abc/xyz/comments` — this is correct and safe.

However, `*` also matches non-UUID strings like `/api/documents/article/comments`. There is a controller endpoint `POST /api/documents/article` — its path `/api/documents/article` does not conflict because the final segment differs. But if a future controller introduced `GET /api/documents/article/comments`, it would be unintentionally `permitAll` even if the intent was authentication-required.

This is a design fragility rather than an immediate vulnerability. The correct fix is to use a more explicit path like `/api/documents/{documentId}/comments` in the security config, which has the same runtime behaviour but documents the intent.

---

## F-22 — JWT Token Response Contains No Token Type or Expiry

**Severity: Low / Info**

**Location:** `AuthResponse.java`

```java
public record AuthResponse(String token) {}
```

The `AuthResponse` contains only the raw token string, with no `token_type`, `expires_in`, or `issued_at` fields. Clients must either hardcode the expiry (6 hours) or parse the JWT to determine when to re-authenticate. When the expiry is changed in configuration, clients have no way to discover the new value without code changes. This also means clients cannot implement proactive token refresh.

**Fix:** Add `expiresIn` (seconds until expiry) and `tokenType` (`"Bearer"`) to `AuthResponse`. This is the OAuth 2.0 token response standard and matches client library expectations.

---

## F-23 — `show-sql=true` in application.properties; SQL Queries Logged in All Environments

**Severity: Medium**

**Location:** `application.properties` line 12

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

SQL logging is enabled unconditionally. In production this logs every query including those containing user IDs, document IDs, and email addresses, to whatever logging backend is configured. If logs are shipped to a third-party aggregator (Splunk, ELK, Datadog), this data is duplicated outside the primary database, expanding the blast radius of a log breach. It also produces significant log volume.

**Fix:** Move to `spring.jpa.show-sql=${APP_SHOW_SQL:false}` so production deployments default to off while development environments can opt in.

---

## Summary Table

| ID | Title | Severity |
|----|-------|----------|
| F-01 | Auth entry point returns empty non-JSON 401 | Medium |
| F-02 | No custom AccessDeniedHandler; default sendError for 403 | Medium |
| F-03 | InvalidTokenException not handled → 500 | High |
| F-04 | UsernameNotFoundException from SecurityUtils not handled → 500 | High |
| F-05 | addComment has no explicit auth guard at security/controller layer | Medium |
| F-06 | ReadingListService.addItem() ignores document visibility | Medium |
| F-07 | PATCH not in CORS allowedMethods | Low |
| F-08 | CORS policy does not cover Swagger UI paths | Low |
| F-09 | No runtime guard against allowedOrigins=* with allowCredentials=true | Medium |
| F-10 | JwtService fail-fast uses misleading exception type; 6h token TTL with no revocation | Low |
| F-11 | No token revocation; stolen JWTs valid for full TTL; no invalidation on password change | High |
| F-12 | No rate limiting on login, register, upload, or interactions endpoints | High |
| F-13 | Swagger UI and OpenAPI spec are publicly accessible in all environments | Medium |
| F-14 | sendError path can create sessions despite STATELESS policy | Low |
| F-15 | No Content-Security-Policy header; Referrer-Policy absent | Low |
| F-16 | BCrypt strength not declared as constant; dummy hash can silently diverge | Low |
| F-17 | JWT filter writes empty body on token rejection (third 401 format) | Low |
| F-18 | Content-Disposition filename not RFC 5987 encoded for non-ASCII | Low |
| F-19 | text/html in allowed content types enables stored XSS via inline file serving | Medium |
| F-20 | Private file streaming has single enforcement layer; no defense-in-depth at security layer | Low |
| F-21 | Ant wildcard pattern in security config could match unintended future paths | Low |
| F-22 | AuthResponse missing token_type and expires_in fields | Low |
| F-23 | show-sql=true unconditionally logs all queries including user data | Medium |
