Now I have full context. Here is the complete implementation plan:

---

# Alexandria Security — Implementation Plan

**Based on audit report:** `/Users/I765724/Documents/alexandria/docs/api-verification/security-config-findings.md`
**Date:** 2026-06-14

---

## 1. Executive Summary

The application is a stateless JWT API backed by Spring Security 6. Most of the foundation is correct: CSRF is intentionally disabled (right call for stateless JWT), session policy is STATELESS, BCrypt is used for passwords, and `@EnableMethodSecurity` is active for method-level guards. The JJWT library version (0.12.6) is current.

The real problems fall into three categories:

**Misconfigured / broken (must fix):**
- Three different 401 response shapes exist simultaneously — one from `JwtAuthenticationFilter` (empty body), one from the `AuthenticationEntryPoint` (`sendError` producing Tomcat HTML), and one from `GlobalExceptionHandler` (JSON). No client can reliably handle all three.
- `InvalidTokenException` and `UsernameNotFoundException` are unhandled in `GlobalExceptionHandler`, causing routine token-rejection and ghost-account scenarios to return 500 with stack traces in logs.
- `ReadingListService.addItem()` does not check document visibility, allowing any authenticated user to bookmark private documents they have no access to.
- `text/html` in allowed upload content types combined with `Content-Disposition: inline` creates a stored XSS vector.
- No rate limiting on `POST /api/auth/login` or `POST /api/auth/register`.

**Accepted trade-offs for a stateless JWT API (no action needed):**
- CSRF disabled — correct, stateless JWT APIs do not use cookies for auth.
- Swagger open — developer/deployment decision; adding a config flag to disable in production is prudent but not a security flaw in the code itself.
- No refresh tokens — accepted simplification for this stage; the 6-hour TTL is long, noted below.
- No `iss`/`aud`/`jti` claims — the JJWT parser validates signature and expiry; the additional claims add defence-in-depth but are not required for correctness in a single-issuer system.

---

## 2. Prioritised Fix List

| # | Finding | Severity | File(s) to Change |
|---|---------|----------|-------------------|
| 1 | F-19: `text/html` + `inline` = stored XSS | **Critical** | `application.properties` |
| 2 | F-03/F-04/F-17: Three 401 shapes; `InvalidTokenException` and `UsernameNotFoundException` fall through to 500 | **High** | `GlobalExceptionHandler.java`, `JwtAuthenticationFilter.java`, `SecurityConfig.java` |
| 3 | F-01/F-02: `sendError` entry point; no custom `AccessDeniedHandler` | **High** | `SecurityConfig.java` + new `SecurityErrorHandler.java` |
| 4 | F-06: `ReadingListService.addItem()` ignores document visibility | **High** | `ReadingListService.java` |
| 5 | F-12: No rate limiting on auth and upload endpoints | **High** | new `RateLimitFilter.java`, `pom.xml` |
| 6 | F-11: Token not invalidated on password change; 6-hour TTL | **High** | `AuthService.java`, `application.properties` |
| 7 | F-09: No multi-origin CORS support; no wildcard guard | **Medium** | `SecurityConfig.java`, `application.properties` |
| 8 | F-23: `show-sql=true` unconditional | **Medium** | `application.properties` |
| 9 | F-05: `POST /api/documents/*/comments` lacks explicit auth guard | **Medium** | `SecurityConfig.java`, `CommentController.java` |
| 10 | F-13: Swagger always public | **Medium** | `SecurityConfig.java`, `application.properties` |
| 11 | F-07: `PATCH` missing from CORS `allowedMethods` | Low | `SecurityConfig.java` |
| 12 | F-08: CORS not registered for Swagger paths | Low | `SecurityConfig.java` |
| 13 | F-14: `sendError` can create sessions despite STATELESS (resolved by fix #3) | Low | `application.properties` |
| 14 | F-15: No `Content-Security-Policy` or `Referrer-Policy` | Low | `SecurityConfig.java` |
| 15 | F-16: BCrypt strength not a constant | Low | `SecurityConfig.java`, `AuthService.java` |
| 16 | F-18: `Content-Disposition` not RFC 5987 encoded | Low | `DocumentController.java` |
| 17 | F-22: `AuthResponse` missing `tokenType`/`expiresIn` | Low | `AuthResponse.java`, `AuthService.java` |
| 18 | F-10: `InvalidTokenException` used as startup config error | Info | `JwtService.java` |
| 19 | F-20: Single visibility enforcement layer for file streaming | Info | `SecurityConfig.java` comment + test |
| 20 | F-21: Ant `*` wildcard in security config is fragile | Info | `SecurityConfig.java` comment |

---

## 3. Implementation Steps

### Step 1 — F-19: Remove `text/html` from allowed content types (Critical, 1 line)

**File:** `application.properties`

```properties
# Before
app.storage.allowed-content-types=application/pdf,text/plain,text/html,application/epub+zip

# After
app.storage.allowed-content-types=application/pdf,text/plain,application/epub+zip
```

If HTML upload is genuinely required, change `DocumentController.streamFile` to force `Content-Disposition: attachment` for all content types (never `inline`). Use Spring's `ContentDisposition` builder (also fixes F-18):

**File:** `DocumentController.java`, method `streamFile`
```java
// Replace the manual header construction with:
String contentDisposition = ContentDisposition.attachment()
    .filename(sfr.originalFilename(), StandardCharsets.UTF_8)
    .build()
    .toString();
return ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(sfr.contentType()))
    .contentLength(sfr.sizeBytes())
    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
    .body(sfr.resource());
```

`ContentDisposition.attachment()` guarantees the browser never renders the file. `ContentDisposition.inline()` must not be used for user-uploaded content.

---

### Step 2 — F-03/F-04/F-17: Unify 401 responses and fix missing exception handlers

**File:** `GlobalExceptionHandler.java` — add two handlers before `handleGeneric`:

```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                        HttpServletRequest request) {
    log.warn("Invalid token on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}

@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("User not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Unauthorized", request);
}
```

Note: `UsernameNotFoundException` must map to 401 (not 404) to avoid user-enumeration — returning 404 on a deleted account confirms the email once existed.

**File:** `JwtAuthenticationFilter.java`, method `doFilterInternal` — replace the empty-body 401 response. Inject `ObjectMapper` and write the `ErrorResponse` JSON directly from the filter (this is the only way to guarantee consistent formatting before Spring MVC runs):

```java
// Add ObjectMapper as a constructor parameter (wired via SecurityConfig @Bean)
private final ObjectMapper objectMapper;

// In the catch block, replace:
response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
return;

// With:
writeUnauthorized(response, request);
return;

// New private method:
private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ErrorResponse body = new ErrorResponse(
        HttpStatus.UNAUTHORIZED.value(),
        HttpStatus.UNAUTHORIZED.getReasonPhrase(),
        "Invalid or expired token",
        Instant.now(),
        request.getRequestURI()
    );
    objectMapper.writeValue(response.getWriter(), body);
}
```

Wire it in `SecurityConfig.jwtAuthenticationFilter` bean method — pass `ObjectMapper` as an additional parameter (it is already a Spring-managed bean and can be injected into `SecurityConfig`).

---

### Step 3 — F-01/F-02: Replace `sendError` with a JSON entry point and access-denied handler

**New class:** `security/SecurityErrorHandler.java`

```java
package com.alexandria.security;

import com.alexandria.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;

@RequiredArgsConstructor
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, request, HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "Access denied");
    }

    private void write(HttpServletResponse response, HttpServletRequest request,
                       HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
            status.value(),
            status.getReasonPhrase(),
            message,
            Instant.now(),
            request.getRequestURI()
        ));
    }
}
```

**File:** `SecurityConfig.java` — replace the `exceptionHandling` lambda and add a `@Bean`:

```java
// Constructor: add ObjectMapper
private final ObjectMapper objectMapper;

// Add to SecurityConfig:
@Bean
public SecurityErrorHandler securityErrorHandler() {
    return new SecurityErrorHandler(objectMapper);
}

// In securityFilterChain, replace:
.exceptionHandling(ex -> ex.authenticationEntryPoint(
    (_, res, _) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))

// With:
.exceptionHandling(ex -> ex
    .authenticationEntryPoint(securityErrorHandler())
    .accessDeniedHandler(securityErrorHandler()))
```

This also resolves F-14 (no more `sendError` → no more accidental session creation through `BasicErrorController`).

---

### Step 4 — F-06: Document visibility check in `ReadingListService.addItem()`

**File:** `ReadingListService.java`, method `addItem`

After the line that fetches `document`, add:

```java
User currentUser = securityUtils.getCurrentUser();
if (document.getVisibility() == Visibility.PRIVATE
        && !document.getAuthor().getId().equals(currentUser.getId())) {
    throw new DocumentNotFoundException(request.documentId());
}
```

Use `DocumentNotFoundException` (not `ForbiddenException`) to stay consistent with the masking strategy used by `DocumentService.get()` — revealing a forbidden document's existence is itself an information leak.

`SecurityUtils` must be injected into `ReadingListService`. Check whether it is already available in `ServiceConfig.java`; if not, expose it as a `@Bean`.

---

### Step 5 — F-12: Rate limiting

**Recommended library:** Bucket4j 8.x with its built-in `jakarta.servlet.Filter` support. Do NOT add the full Spring Boot starter with Redis unless the deployment already has Redis. Use the in-memory (local JVM) variant initially — it is sufficient for a single-instance deployment and does not introduce a new infrastructure dependency.

**pom.xml:** Add one dependency:

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
```

**New class:** `security/RateLimitFilter.java`

```java
package com.alexandria.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.local.LocalBucketBuilder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimitFilter implements Filter {

    // ConcurrentHashMap<ip, Bucket> — keyed by IP per route class
    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> registerBuckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = resolveClientIp(request);

        Bucket bucket = null;
        if (HttpMethod.POST.matches(method) && "/api/auth/login".equals(path)) {
            bucket = loginBuckets.computeIfAbsent(ip, k -> loginBucket());
        } else if (HttpMethod.POST.matches(method) && "/api/auth/register".equals(path)) {
            bucket = registerBuckets.computeIfAbsent(ip, k -> registerBucket());
        }

        if (bucket != null && !bucket.tryConsume(1)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Rate limit exceeded\",\"path\":\"" + path + "\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private static Bucket loginBucket() {
        // 5 per minute, 20 per hour
        return Bucket.builder()
            .addLimit(Bandwidth.simple(5, Duration.ofMinutes(1)))
            .addLimit(Bandwidth.simple(20, Duration.ofHours(1)))
            .build();
    }

    private static Bucket registerBucket() {
        // 3 per hour
        return Bucket.builder()
            .addLimit(Bandwidth.simple(3, Duration.ofHours(1)))
            .build();
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
```

Register it in `SecurityConfig` before the JWT filter:

```java
// In SecurityConfig.securityFilterChain, after addFilterBefore for JwtAuthenticationFilter:
.addFilterBefore(new RateLimitFilter(), JwtAuthenticationFilter.class)
```

Or register it as a `FilterRegistrationBean` with `setOrder(Ordered.HIGHEST_PRECEDENCE)` in a config class if it must run outside the Spring Security filter chain (preferred for auth endpoints).

Note on in-memory buckets and ConcurrentHashMap: the map will grow unbounded if many unique IPs hit the server. For production, cap it with a Caffeine cache (evict entries older than 1 hour) or switch to Redis-backed Bucket4j. This is the one place where "add Redis later" is a natural upgrade path.

---

### Step 6 — F-11: Reduce token lifetime

**File:** `application.properties`

```properties
# Before
jwt.expiration=21600000

# After — 30 minutes default, operator can override
jwt.expiration=${JWT_EXPIRATION:1800000}
```

Full token revocation (blacklist or per-user version counter) is a separate workstream. The minimum viable step is reducing the default TTL so that a leaked token's window of abuse is narrowed from 6 hours to 30 minutes.

If per-user invalidation on password change is required in the same sprint, embed a `tokenVersion` claim: add a `tokenVersion` (integer) column to the `users` table, increment it on `PUT /api/users/{id}` (password change), and validate `claims.get("version", Integer.class).equals(user.getTokenVersion())` in `JwtAuthenticationFilter`.

---

### Step 7 — F-09: Multi-origin CORS support

**File:** `application.properties`

```properties
# Before
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:4200}

# After
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:4200}
# Operators set: APP_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

**File:** `SecurityConfig.java` — change the field type and constructor, and add a startup guard:

```java
private final List<String> allowedOrigins;

public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOriginsRaw,
                      ObjectMapper objectMapper) {
    this.allowedOrigins = Arrays.stream(allowedOriginsRaw.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .toList();
    if (this.allowedOrigins.contains("*")) {
        throw new IllegalStateException(
            "APP_CORS_ALLOWED_ORIGINS must not be '*' when credentials are enabled. " +
            "Set explicit origins.");
    }
    this.objectMapper = objectMapper;
}
```

In `corsConfigurationSource()`, replace the single-string `List.of(allowedOrigins)` with the already-parsed `allowedOrigins` list:

```java
config.setAllowedOrigins(allowedOrigins);
```

Throwing `IllegalStateException` in the constructor means the application refuses to start if an operator misconfigures the wildcard — consistent with the fail-fast principle already used for the JWT secret.

---

### Step 8 — F-23: SQL logging controlled by environment

**File:** `application.properties`

```properties
# Before
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# After
spring.jpa.show-sql=${APP_SHOW_SQL:false}
spring.jpa.properties.hibernate.format_sql=${APP_SHOW_SQL:false}
```

Set `APP_SHOW_SQL=true` in local `.env` / Docker Compose for development.

---

### Step 9 — F-05: Explicit auth guard for `POST /api/documents/*/comments`

**File:** `SecurityConfig.java` — in `authorizeHttpRequests`, add an explicit rule before `anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.POST, "/api/documents/{documentId}/comments").authenticated()
```

Use `{documentId}` (named variable) rather than `*` (anonymous wildcard) to make intent readable. Runtime behaviour is identical for Ant matching but documents the expected type.

**File:** `CommentController.java` — add annotation to the `addComment` method:

```java
@PreAuthorize("isAuthenticated()")
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(...) { ... }
```

This makes the access requirement visible in the controller, not just in the security config.

---

### Step 10 — F-13: Swagger controlled by environment

**File:** `application.properties`

```properties
app.swagger.enabled=${APP_SWAGGER_ENABLED:true}
```

**File:** `SecurityConfig.java` — inject and use the flag:

```java
private final boolean swaggerEnabled;

// In constructor, add: @Value("${app.swagger.enabled}") boolean swaggerEnabled

// In securityFilterChain, replace the unconditional permitAll:
if (swaggerEnabled) {
    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
} else {
    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").denyAll();
}
```

Production deployments set `APP_SWAGGER_ENABLED=false`.

---

## 4. CORS Multi-Origin Fix (Detail)

The current code passes a single `String` to `List.of(allowedOrigins)`. When an operator sets `APP_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com`, the entire comma-separated string is treated as one literal origin, matching nothing.

The fix in Step 7 above is the complete solution. The split-and-trim pattern:

```java
Arrays.stream(allowedOriginsRaw.split(","))
    .map(String::trim)
    .filter(s -> !s.isBlank())
    .toList()
```

This correctly handles:
- Single origin: `https://app.example.com` → `["https://app.example.com"]`
- Multiple origins: `https://app.example.com, https://admin.example.com` → `["https://app.example.com", "https://admin.example.com"]`
- Accidental whitespace: spaces around commas are trimmed.
- Empty trailing comma: filtered by `!s.isBlank()`.

The wildcard guard (`if (allowedOrigins.contains("*")) throw`) is placed in the constructor so the application refuses to start rather than silently allowing an insecure combination at request time.

If subdomain wildcards are ever required (e.g., `https://*.example.com`), switch from `setAllowedOrigins` to `setAllowedOriginPatterns`. These are mutually exclusive — `setAllowedOriginPatterns` accepts glob patterns, `setAllowedOrigins` does not.

---

## 5. Rate Limiting Approach

**Library:** Bucket4j 8.x (core only, `bucket4j-core`). No Spring Boot starter, no Redis dependency.

**Rationale:** Bucket4j's token-bucket algorithm is the industry standard for this use case. The core library is ~150 KB, has no transitive runtime dependencies, and is thread-safe. The in-memory implementation is appropriate for a single-instance deployment. Redis-backed `ProxyManager` is a drop-in upgrade when horizontal scaling is needed.

**Placement:** A `jakarta.servlet.Filter` (not an `@Aspect`). Reasons:
- Filters run before Spring MVC, before controllers, before `@PreAuthorize` — rate limiting happens as early as possible.
- The filter can write a 429 response directly without going through Spring's dispatch machinery.
- Aspects on `@Service` or `@Controller` methods execute after the request is already parsed, which means the BCrypt work for a login attempt has already started by the time an aspect could reject it — defeating the purpose of rate limiting the CPU cost.

**Implementation:** See Step 5 above for the full `RateLimitFilter` class.

**Upgrade path to Redis:** Replace the `ConcurrentHashMap<String, Bucket>` fields with `ProxyManager<String>` backed by `LettuceBasedProxyManager` from `bucket4j-redis-lettuce`. The limit definitions (`loginBucket()`, `registerBucket()`) do not change.

**Do not use:** `spring-boot-starter-data-redis` or a `RateLimiter` from Resilience4j for this purpose. Resilience4j's `RateLimiter` is designed for outbound call protection (thread permit-based), not inbound IP-keyed buckets.

---

## 6. Security Headers

**Spring Security provides by default** (no configuration needed):
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
- `Pragma: no-cache`
- `Expires: 0`
- `X-XSS-Protection: 0` (disabled as per current guidance; the old `1; mode=block` is counterproductive in modern browsers)

**Must be added explicitly** (F-15):

In `SecurityConfig.securityFilterChain`, add a `headers` block after `sessionManagement`:

```java
.headers(headers -> headers
    .referrerPolicy(referrer ->
        referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
    .contentSecurityPolicy(csp ->
        csp.policyDirectives("default-src 'none'"))
)
```

`default-src 'none'` is correct for a pure REST API — no scripts, styles, or frames should ever be loaded from this origin. For Swagger UI paths, a more permissive policy is needed because Swagger UI loads inline scripts. Two options:
- Accept a looser policy for all paths: `"default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'"`.
- Add a `HandlerInterceptor` that overrides the `Content-Security-Policy` header specifically for `/swagger-ui/**` paths.

For a REST API where Swagger is disabled in production (Step 10), `default-src 'none'` is safe and no per-path override is needed.

**`Permissions-Policy`:** Not provided by Spring Security by default. Low value for a REST API — omit unless a security scanner requires it.

---

## 7. Recommended Implementation Order

Execute in this sequence to unblock each subsequent step and avoid partial-fix states where error handling is partially correct:

| Order | Finding(s) | Why this order |
|-------|-----------|----------------|
| 1 | F-19 (remove `text/html`, force `attachment`) | Zero-dependency config change; highest impact |
| 2 | F-23 (`show-sql` env-controlled) | One-liner; immediately reduces log exposure |
| 3 | F-03 + F-04 (`GlobalExceptionHandler` additions) | Establishes the correct 401 JSON shape before touching filters |
| 4 | F-01 + F-02 + F-17 (`SecurityErrorHandler`, filter body) | All three 401 paths now produce identical JSON |
| 5 | F-09 (CORS multi-origin + wildcard guard) | Multi-origin split needed before adding more origins |
| 6 | F-07 + F-08 (PATCH + Swagger CORS) | Small additions to the CORS config already being edited |
| 7 | F-05 (explicit comment auth guard) | Low-risk annotation + config addition |
| 8 | F-06 (`ReadingListService` visibility check) | Access-control fix, needs `SecurityUtils` injection |
| 9 | F-12 (Bucket4j rate limiting) | New filter; can be deployed independently |
| 10 | F-11 (reduce JWT TTL to 30 min) | `application.properties` change; coordinate with frontend |
| 11 | F-13 (Swagger env flag) | Config + conditional security rule |
| 12 | F-14 (`server.error.whitelabel`, session timeout) | Defensive hardening; resolved structurally by step 4 |
| 13 | F-15 (CSP + Referrer-Policy headers) | Header additions to `SecurityConfig` |
| 14 | F-16 (BCrypt constant + dummy hash test) | Code-quality fix; add unit test |
| 15 | F-18 (RFC 5987 `Content-Disposition`) | One-line `DocumentController` change; done alongside F-19 |
| 16 | F-22 (`AuthResponse` `expiresIn`/`tokenType`) | Client API addition; coordinate with frontend |
| 17 | F-10 (`IllegalStateException` at startup) | Cosmetic improvement |
| 18 | F-20 + F-21 (comments + integration test) | Documentation and test coverage |

---

## 8. Acceptable As-Is

The following findings from the audit require no code change:

**CSRF disabled (`AbstractHttpConfigurer::disable`):** Correct. The application uses stateless JWT authentication delivered via the `Authorization: Bearer` header, not cookies. There is no session to hijack via a cross-site forged request. Re-enabling CSRF would require implementing a CSRF token endpoint and add complexity with no security benefit for this architecture.

**Swagger open in development (`APP_SWAGGER_ENABLED` defaults to `true`):** The default is `true` for developer convenience and is correct for a development/CI environment. Step 10 of this plan adds a config flag to disable it in production. No code is wrong; the missing piece is the feature flag.

**No `iss`/`aud`/`jti` JWT claims:** For a single-issuer, single-audience API with no token exchange or federated identity, these claims add maintenance cost without changing the threat model. The JJWT parser already validates the cryptographic signature and `exp` claim. If a token inspection / revocation service is added later, adding `jti` at that point is the natural time to do it.

**`anyRequest().authenticated()` as catch-all:** This is the correct default posture. All endpoints not explicitly listed are authentication-required. The rule order (explicit rules before `anyRequest`) is consistent with Spring Security's evaluation model.

**`@EnableMethodSecurity` active:** Correct. `@PreAuthorize` on services and controllers provides a second enforcement layer independent of the URL-based rules in `SecurityConfig`. This is defence-in-depth, not redundancy.

**`SessionCreationPolicy.STATELESS`:** Correct for JWT. The session creation side-effect (F-14) is fully resolved by removing `sendError` (Step 4).
