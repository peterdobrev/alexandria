# Alexandria — Security Audit: CORS, CSRF, and HTTP Security Headers

**Date:** 2026-06-14
**Scope:** CORS configuration, CSRF posture, HTTP security headers, session management, Swagger/Actuator exposure, preflight handling, JWT token leakage, rate limiting, and logout/token invalidation.
**Primary file:** `SecurityConfig.java`
**Spring Boot version:** 4.0.6 (uses Spring Security 7.x)

---

## Summary Table

| ID | Title | Severity |
|----|-------|----------|
| [SH-01](#sh-01) | CORS `allowedHeaders` is a wildcard — any request header permitted | MEDIUM |
| [SH-02](#sh-02) | CORS `exposedHeaders` not configured — response headers hidden from browser JS | LOW |
| [SH-03](#sh-03) | CORS `maxAge` not configured — browsers repeat preflight on every request | LOW |
| [SH-04](#sh-04) | CORS paths cover `/swagger-ui/**` and `/v3/api-docs/**` with credentials allowed | MEDIUM |
| [SH-05](#sh-05) | No OPTIONS `permitAll` rule — preflight requests blocked by `anyRequest().authenticated()` for non-whitelisted paths | HIGH |
| [SH-06](#sh-06) | CSRF disabled without a code comment explaining the stateless JWT rationale | LOW |
| [SH-07](#sh-07) | HSTS missing `preload` directive and no `Expect-CT` consideration | LOW |
| [SH-08](#sh-08) | `Content-Security-Policy: default-src 'none'` breaks Swagger UI (inline scripts) | MEDIUM |
| [SH-09](#sh-09) | `X-Frame-Options` and `X-Content-Type-Options` default headers may be suppressed by partial `headers()` override | MEDIUM |
| [SH-10](#sh-10) | `Permissions-Policy` header not set | LOW |
| [SH-11](#sh-11) | `Cross-Origin-Resource-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy` headers absent | LOW |
| [SH-12](#sh-12) | Session management correctly `STATELESS` but `sendError()` in `JwtAuthenticationFilter` can re-create sessions | LOW |
| [SH-13](#sh-13) | Swagger UI (`/swagger-ui/**`, `/v3/api-docs/**`) permanently `permitAll` in all environments | MEDIUM |
| [SH-14](#sh-14) | Spring Actuator: `management.endpoint.env.keys-to-sanitize` configured but no `management.endpoints.web.exposure` restriction | MEDIUM |
| [SH-15](#sh-15) | `JwtAuthenticationFilter` passes the raw exception message into `BadCredentialsException` before handing to the entry point | LOW |
| [SH-16](#sh-16) | No logout endpoint and no token invalidation mechanism | HIGH |
| [SH-17](#sh-17) | No rate limiting on authentication endpoints | HIGH |
| [SH-18](#sh-18) | `app.cors.allowed-origins` default is `localhost:4200` with no production-safe default | LOW |
| [SH-19](#sh-19) | `PUT /api/users/{id}` lacks an explicit HTTP-layer auth rule; relies solely on `@PreAuthorize` | LOW |
| [SH-20](#sh-20) | `Cache-Control` headers not explicitly configured for API responses containing sensitive user data | LOW |

---

## Findings

---

### SH-01

**CORS `allowedHeaders` is a wildcard — any request header is permitted**

**Severity: MEDIUM**
**File:** `SecurityConfig.java`, line 67

```java
config.setAllowedHeaders(List.of("*"));
```

The wildcard `"*"` permits any request header in a CORS preflight response. While this is common practice for APIs, it means a malicious cross-origin page can include custom headers such as `X-Custom-Override`, `X-HTTP-Method-Override`, or any header used by proxy infrastructure without restriction. In the worst case, if a future reverse proxy uses a custom header for privilege escalation (e.g., `X-Internal-Role: admin`), a cross-origin attacker can include that header in a credentialed request.

**What should be done:** Replace with an explicit allowlist that covers only what the frontend actually sends:

```java
config.setAllowedHeaders(List.of(
    "Authorization",
    "Content-Type",
    "Accept",
    "Origin",
    "X-Requested-With"
));
```

If the frontend does not use cookies or any non-standard header, restricting this is a zero-friction change.

---

### SH-02

**CORS `exposedHeaders` not configured — response headers hidden from browser JavaScript**

**Severity: LOW**
**File:** `SecurityConfig.java`, lines 63–73

```java
CorsConfiguration config = new CorsConfiguration();
// exposedHeaders not set
config.setAllowedOrigins(allowedOrigins);
```

The CORS specification restricts which response headers are visible to browser JavaScript unless they are listed in `Access-Control-Expose-Headers`. By default only the seven "safelisted" response headers are accessible (`Cache-Control`, `Content-Language`, `Content-Length`, `Content-Type`, `Expires`, `Last-Modified`, `Pragma`). Headers such as `Location` (returned on `POST /api/auth/register` → 201 Created), `Allow`, and any custom headers are invisible to the frontend unless explicitly exposed.

If the frontend JavaScript reads the `Location` header on registration to obtain the created user's URL, it will silently receive `null` in all browsers.

**What should be done:**

```java
config.setExposedHeaders(List.of("Location"));
```

Enumerate only the headers the frontend actually reads.

---

### SH-03

**CORS `maxAge` not configured — browsers re-issue a preflight on every cross-origin request**

**Severity: LOW**
**File:** `SecurityConfig.java`, lines 63–73

`CorsConfiguration.maxAge` defaults to `null`. When `Access-Control-Max-Age` is absent from the preflight response, browsers use their built-in default (5 seconds in Chrome, 0 in some other browsers), causing a preflight `OPTIONS` request before every single cross-origin API call. For a document-heavy API with list/search/read endpoints this doubles the number of HTTP round-trips for every browser session.

**What should be done:**

```java
config.setMaxAge(3600L); // 1 hour
```

This is safe because the allowed methods and headers change only at deployment time.

---

### SH-04

**CORS `allowCredentials=true` applied to Swagger and API-docs paths**

**Severity: MEDIUM**
**File:** `SecurityConfig.java`, lines 69–73

```java
source.registerCorsConfiguration("/api/**", config);
source.registerCorsConfiguration("/swagger-ui/**", config);
source.registerCorsConfiguration("/v3/api-docs/**", config);
```

The same `CorsConfiguration` instance — which has `allowCredentials=true` — is applied to the Swagger UI and OpenAPI spec paths. `allowCredentials=true` means the browser will include cookies and HTTP authentication in cross-origin requests. Swagger UI at `/swagger-ui/**` is a publicly accessible HTML/JS application. There is no legitimate reason for a cross-origin page to make credentialed requests to the Swagger UI or the OpenAPI spec document.

Applying `allowCredentials=true` to public documentation paths widens the credentialed CORS surface without any benefit.

**What should be done:** Create a separate `CorsConfiguration` for the documentation paths without `allowCredentials`:

```java
CorsConfiguration docConfig = new CorsConfiguration();
docConfig.setAllowedOrigins(allowedOrigins);
docConfig.setAllowedMethods(List.of("GET", "OPTIONS"));
docConfig.setAllowedHeaders(List.of("Accept"));
docConfig.setAllowCredentials(false);
docConfig.setMaxAge(3600L);

source.registerCorsConfiguration("/api/**", config);           // credentials=true
source.registerCorsConfiguration("/swagger-ui/**", docConfig); // credentials=false
source.registerCorsConfiguration("/v3/api-docs/**", docConfig);
```

---

### SH-05

**No `OPTIONS` `permitAll` rule — CORS preflight requests for authenticated paths are blocked**

**Severity: HIGH**
**File:** `SecurityConfig.java`, lines 93–107

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
    .requestMatchers(HttpMethod.GET, "/api/documents", ...).permitAll()
    // ... no OPTIONS rule ...
    .anyRequest().authenticated()
)
```

Spring Security 6+ includes built-in handling to `permitAll` CORS preflight `OPTIONS` requests via `CorsFilter`, which runs before the authorization filter. This means that for paths where CORS is registered, Spring's `CorsFilter` intercepts the `OPTIONS` preflight, processes it, and returns the CORS response without reaching the authorization layer. The CORS filter is installed early in the filter chain when `.cors(...)` is configured.

**However**, this only applies to paths that are covered by the `CorsConfigurationSource`. For any path not covered by the registered CORS mappings (e.g., a future endpoint at a path not matching `/api/**`), an `OPTIONS` preflight will reach `anyRequest().authenticated()` and receive a `401`, causing all cross-origin requests to that path to fail silently in the browser.

Additionally, there is no explicit `requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()` as a safety net for paths added in the future before the CORS source is updated.

**What should be done:** Add an explicit `OPTIONS` permit-all as a defensive measure:

```java
.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
```

This should appear as the first rule in `authorizeHttpRequests`. It is safe because CORS preflight requests carry no credentials and are used only for browser negotiation; the actual credentialed request that follows is still checked for authentication.

---

### SH-06

**CSRF disabled without an explanatory comment**

**Severity: LOW**
**File:** `SecurityConfig.java`, line 82

```java
.csrf(AbstractHttpConfigurer::disable)
```

CSRF protection is correctly disabled for a stateless JWT API. The `Authorization: Bearer <token>` header is a non-simple CORS header that browsers never send automatically, which eliminates the CSRF threat model. Sessions are not used (`STATELESS`). Disabling CSRF is the right call.

**What is wrong:** There is no comment explaining the reasoning. A future developer or security scanner will flag this line as a vulnerability without understanding the architectural context.

**What should be done:** Add a short comment:

```java
// CSRF disabled — stateless JWT authentication via Authorization header (not cookies).
// Browsers cannot attach a Bearer token automatically; no CSRF threat exists.
.csrf(AbstractHttpConfigurer::disable)
```

---

### SH-07

**HSTS missing `preload` directive; no consideration of `Expect-CT`**

**Severity: LOW**
**File:** `SecurityConfig.java`, lines 86–88

```java
.httpStrictTransportSecurity(hsts -> hsts
    .includeSubDomains(true)
    .maxAgeInSeconds(31536000))
```

HSTS is correctly configured with `includeSubDomains` and a one-year `maxAge`. Two gaps remain:

1. **No `preload` directive.** Without `preload`, a user who visits the API for the first time over HTTP (before receiving the HSTS header) is still vulnerable to a TLS downgrade or SSL-strip attack on first contact. Adding `.preload(true)` to the HSTS configuration and submitting the domain to the HSTS preload list ([hstspreload.org](https://hstspreload.org)) eliminates first-visit vulnerability entirely. Note: `preload` requires `includeSubDomains` (already set) and `maxAge >= 31536000` (already set).

2. **No `Expect-CT` header.** `Expect-CT` (Certificate Transparency enforcement) notifies clients if a fraudulent certificate is presented. While browsers now enforce CT natively, an explicit `Expect-CT: max-age=86400, enforce` header provides an additional signal.

**What should be done:**

```java
.httpStrictTransportSecurity(hsts -> hsts
    .includeSubDomains(true)
    .maxAgeInSeconds(31536000)
    .preload(true))
```

Then submit the domain at https://hstspreload.org when deploying to production. Note that `preload` cannot be safely enabled during development if the server also runs over plain HTTP on localhost — gate this behind a production Spring profile.

---

### SH-08

**`Content-Security-Policy: default-src 'none'` breaks Swagger UI**

**Severity: MEDIUM**
**File:** `SecurityConfig.java`, lines 91–92

```java
.contentSecurityPolicy(csp ->
    csp.policyDirectives("default-src 'none'"))
```

`default-src 'none'` forbids loading any resource: scripts, styles, images, fonts, frames, and XHR connections. This is correct for a pure REST API that returns only JSON. However, Swagger UI at `/swagger-ui/**` is an HTML application that:

- Loads inline JavaScript and CSS
- Makes XMLHttpRequests to `/v3/api-docs/**`
- Loads fonts from CDN (in the default Springdoc configuration)

With `default-src 'none'`, Swagger UI will render a blank page and all its JS will be blocked. The browser's CSP violation reports will show:

```
Refused to execute inline script because it violates the following
Content Security Policy directive: "default-src 'none'"
```

This is a broken-by-design configuration for the current setup: Swagger is `permitAll` on the authorization side but its UI is completely non-functional due to the CSP.

**What should be done:** Two approaches, ordered by security preference:

**Option A (preferred): Disable Swagger in production**

```java
// In SecurityConfig, make Swagger conditional on a profile or property
// Then keep CSP as 'none' for all endpoints
```

When Swagger is disabled in production (see SH-13), the strict `default-src 'none'` is correct and poses no usability issues. This is the right fix.

**Option B: Per-path CSP overrides (if Swagger must remain enabled)**

Use a `HandlerInterceptor` or a custom `Filter` to send a permissive policy only for `/swagger-ui/**`:

```java
// Permissive only for Swagger UI paths
"default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'"
```

The Spring Security `contentSecurityPolicy()` DSL applies a single policy globally and does not support per-path overrides, so a servlet filter or interceptor is required for Option B.

---

### SH-09

**Partial `headers()` override may suppress Spring Security default headers**

**Severity: MEDIUM**
**File:** `SecurityConfig.java`, lines 85–92

```java
.headers(headers -> headers
    .httpStrictTransportSecurity(hsts -> hsts
            .includeSubDomains(true)
            .maxAgeInSeconds(31536000))
    .referrerPolicy(referrer ->
            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
    .contentSecurityPolicy(csp ->
            csp.policyDirectives("default-src 'none'")))
```

In Spring Security 6+, calling `.headers(headers -> headers.someCustomizer(...))` **without** calling `.headers(headers -> headers.defaultsDisabled().someCustomizer(...))` leaves the default headers active. The Spring Security 7.x defaults include:

- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `X-XSS-Protection: 0`
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
- `Pragma: no-cache`
- `Expires: 0`

These defaults are **additive** to the explicit configuration above, so the code as-written should have all defaults active plus the explicit HSTS, CSP, and Referrer-Policy additions.

**The risk is subtle:** If the application is migrated to a newer Spring Security major version that changes which headers are defaults-on versus defaults-off, the implicit reliance on default behaviour will silently drop headers. There is currently no test asserting that specific security headers are present in responses, so this would be invisible.

**What should be done:** Make the header configuration explicit and exhaustive:

```java
.headers(headers -> headers
    .contentTypeOptions(Customizer.withDefaults())           // X-Content-Type-Options: nosniff
    .frameOptions(frame -> frame.deny())                     // X-Frame-Options: DENY
    .httpStrictTransportSecurity(hsts -> hsts
            .includeSubDomains(true)
            .maxAgeInSeconds(31536000)
            .preload(true))
    .referrerPolicy(referrer ->
            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
    .contentSecurityPolicy(csp ->
            csp.policyDirectives("default-src 'none'")))
```

This makes every header an explicit intent rather than an inherited side-effect, and ensures the same headers are applied regardless of what the default set includes in future Spring Security versions.

---

### SH-10

**`Permissions-Policy` header not set**

**Severity: LOW**
**File:** `SecurityConfig.java`

Spring Security does not include `Permissions-Policy` in its default header set. This header restricts browser feature APIs (camera, microphone, geolocation, payment, USB, etc.) for the page served from this origin.

For a REST API returning JSON, `Permissions-Policy` is irrelevant because no browser features can be invoked from a JSON response. However, the Swagger UI served at `/swagger-ui/**` is rendered HTML in a browser context. Without a `Permissions-Policy`, Swagger UI could theoretically be used as a vector for feature-access abuse (e.g., a stored-XSS payload calling `navigator.mediaDevices`).

**What should be done:**

```java
.headers(headers -> headers
    // ... existing headers ...
    .addHeaderWriter(new StaticHeadersWriter(
        "Permissions-Policy",
        "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
    ))
)
```

This is low-priority for a pure API but straightforward to add.

---

### SH-11

**`Cross-Origin-Resource-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy` headers absent**

**Severity: LOW**
**File:** `SecurityConfig.java`

Three headers that implement the "cross-origin isolation" model are missing:

- **`Cross-Origin-Resource-Policy: same-site`** (or `same-origin`) — prevents other origins from loading resources from this server (e.g., embedding the API response in an `<img src>` tag to perform Spectre-style timing attacks). For a JSON API, `same-site` or `same-origin` is appropriate.
- **`Cross-Origin-Opener-Policy: same-origin`** — prevents the API from being opened in a popup and reading `window.opener`. Only relevant if any endpoint serves HTML (Swagger UI does).
- **`Cross-Origin-Embedder-Policy: require-corp`** — part of cross-origin isolation; allows `SharedArrayBuffer` if needed.

**What should be done:** At minimum, set `Cross-Origin-Resource-Policy`:

```java
.addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin"))
.addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
```

`Cross-Origin-Embedder-Policy` should only be added if the application requires shared memory features.

---

### SH-12

**Session correctly `STATELESS` but `JwtAuthenticationFilter` delegates to entry point which may trigger error dispatch**

**Severity: LOW**
**File:** `SecurityConfig.java` line 84; `JwtAuthenticationFilter.java` lines 49–55

Session management is correctly set:

```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

However, when an invalid JWT is detected, `JwtAuthenticationFilter` calls:

```java
entryPoint.commence(request, response,
        new BadCredentialsException(e.getMessage(), e));
return;
```

`JsonAuthenticationEntryPoint.commence` writes the JSON response directly to `response.getOutputStream()` and sets the status code, which is correct — it does **not** call `sendError()`. The session STATELESS contract is therefore preserved.

**Residual risk:** The `JsonAuthenticationEntryPoint` does not call `response.flushBuffer()` or `response.setContentLength()` after writing. On Tomcat with certain connector configurations, the response buffer may not be committed before the filter returns, leaving a window where the container could append a default error body. This is extremely low probability but is good practice to address.

**What should be done:** After `objectMapper.writeValue(response.getOutputStream(), body)`, add:

```java
response.getOutputStream().flush();
```

This ensures the response is committed before the filter returns.

---

### SH-13

**Swagger UI and OpenAPI spec permanently `permitAll` in all environments**

**Severity: MEDIUM**
**File:** `SecurityConfig.java`, line 102; `application.properties` (no `APP_SWAGGER_ENABLED` property)

```java
.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
```

The OpenAPI spec at `/v3/api-docs/openapi.json` exposes the full API contract without authentication:
- All endpoint paths, HTTP methods, and URL parameters
- All request and response DTO schemas including field names and validation constraints
- The JWT bearer security scheme configuration
- Error response shapes that can inform exploit crafting

There is no environment variable, Spring profile, or feature flag to disable this in production. The `application.properties` has no `app.swagger.enabled` property.

**What should be done:**

1. Add a property to `application.properties`:

```properties
app.swagger.enabled=${APP_SWAGGER_ENABLED:true}
```

2. In `SecurityConfig`, inject the flag and conditionalize the rule:

```java
@Value("${app.swagger.enabled}")
private boolean swaggerEnabled;

// In securityFilterChain's authorizeHttpRequests:
if (swaggerEnabled) {
    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
} else {
    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").denyAll();
}
```

3. Set `APP_SWAGGER_ENABLED=false` in the production environment.

---

### SH-14

**Actuator: env endpoint key sanitization configured but web exposure restriction is absent**

**Severity: MEDIUM**
**File:** `application.properties`, line 23

```properties
management.endpoint.env.keys-to-sanitize=jwt.secret,spring.datasource.password
```

This property sanitizes two specific keys in the `/actuator/env` response. Its presence implies the `/actuator/env` endpoint is reachable — otherwise the sanitization configuration would be irrelevant.

Spring Boot's default actuator configuration exposes only `/actuator/health` over HTTP. If `spring-boot-actuator` (or `spring-boot-starter-actuator`) is on the classpath and `management.endpoints.web.exposure.include` is not explicitly restricted, the exposure depends on the Spring Boot version and auto-configuration defaults. In Spring Boot 3.x/4.x the default is `health` only for web; however, this can change if Spring Boot DevTools or a monitoring starter is added to the classpath.

The `management.endpoint.env.keys-to-sanitize` line proves that someone intended the `/actuator/env` endpoint to be available (or to be safe if accidentally made available). The sanitization list covers only two keys and misses:
- `spring.datasource.url` (contains host, port, and database name)
- `app.cors.allowed-origins` (reveals frontend URLs)
- `jwt.issuer`, `jwt.audience`, `jwt.expiration` (reveals token configuration)
- `APP_STORAGE_ROOT` (reveals server filesystem paths)

**What should be done:**

```properties
# Restrict which actuator endpoints are exposed over HTTP
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never

# Keep the existing sanitization as defense-in-depth
management.endpoint.env.keys-to-sanitize=jwt.secret,spring.datasource.password,spring.datasource.url
```

If `/actuator/env` is genuinely needed for operations tooling, it should be placed behind the Spring Security filter chain with an `ADMIN` role requirement:

```java
.requestMatchers("/actuator/env", "/actuator/info").hasRole("ADMIN")
.requestMatchers("/actuator/health").permitAll()
.requestMatchers("/actuator/**").denyAll()
```

---

### SH-15

**`JwtAuthenticationFilter` passes the raw internal exception message into the entry point**

**Severity: LOW**
**File:** `JwtAuthenticationFilter.java`, line 53

```java
entryPoint.commence(request, response,
        new BadCredentialsException(e.getMessage(), e));
```

The caught exceptions are `InvalidTokenException`, `IllegalArgumentException`, and `UsernameNotFoundException`. Their messages are:

- `InvalidTokenException`: `"JWT token has expired"` or `"JWT token is invalid or malformed"` (from `JwtService`)
- `UsernameNotFoundException`: `"User not found: " + email` (from `UserDetailsServiceImpl`)
- `IllegalArgumentException`: varies, potentially includes internal stack context

The `JsonAuthenticationEntryPoint.commence` method does **not** use `authException.getMessage()` — it sends a fixed `"Authentication required"` string to the client:

```java
ErrorResponse body = new ErrorResponse(
    HttpStatus.UNAUTHORIZED.value(),
    HttpStatus.UNAUTHORIZED.getReasonPhrase(),
    "Authentication required",           // fixed string, not authException.getMessage()
    Instant.now(),
    request.getRequestURI()
);
```

This means the raw exception message is **not** sent to the client. The finding is LOW severity because there is no current leakage.

**Residual risk:** The `BadCredentialsException` wraps the internal message as its own `getMessage()`. If the entry point implementation ever changes to use `authException.getMessage()` (a very natural refactoring), the email address from `UsernameNotFoundException("User not found: user@example.com")` or the JWT detail from `InvalidTokenException` would be exposed to the client. The `UsernameNotFoundException` case is especially sensitive — it leaks the email address of the caller.

**What should be done:** Construct the `BadCredentialsException` without propagating the internal message:

```java
// In JwtAuthenticationFilter catch block, instead of:
new BadCredentialsException(e.getMessage(), e)

// Use:
new BadCredentialsException("Authentication failed", e)
```

This ensures no internal message can ever reach the client through the exception, regardless of future entry point changes.

---

### SH-16

**No logout endpoint and no token invalidation mechanism**

**Severity: HIGH**
**File:** `AuthController.java` (no `POST /api/auth/logout`), `JwtService.java`

There is no logout endpoint. Issued JWTs are valid until their `exp` claim is reached. The default expiry is configured as:

```properties
jwt.expiration=${JWT_EXPIRATION:1800000}
```

30 minutes (1,800,000 ms). Once a token is issued, there is no server-side mechanism to revoke it. The consequences:

1. **Logout is client-only.** The client can discard the token from memory/storage, but the token remains valid on the server. If an attacker captured the token before the user "logged out", they retain access for the full TTL.

2. **Password change does not invalidate existing sessions.** `PUT /api/users/{id}` updates the user record but issues no new token version. An attacker with a stolen token retains access for up to 30 minutes after the victim changes their password — possibly longer if the TTL was recently renewed.

3. **Account deletion/deactivation does not stop token use.** If a user account is deleted, their JWT remains valid until expiry.

**What should be done (incremental):**

**Minimum viable:** Add a `POST /api/auth/logout` endpoint that is a no-op server-side but signals to the client to discard the token. Document clearly that server-side revocation is not implemented. This is not a security improvement but sets correct API contract expectations.

**Better:** Implement per-user token versioning. Add a `token_version` integer column to the `users` table (default `0`). Embed `"version": tokenVersion` in JWT claims at issuance. In `JwtAuthenticationFilter`, after extracting claims, compare `claims.get("version", Integer.class)` with `user.getTokenVersion()`. Increment `tokenVersion` on password change and on explicit logout. This invalidates all tokens for a user without maintaining a blocklist.

**Full revocation:** Maintain a short-lived token blocklist (e.g., Redis set keyed by `jti` claim, expiring at the token's `exp`). The JWT already includes a `jti` claim (UUID generated in `JwtService.generateToken()`), so the infrastructure for blocklisting is in place.

---

### SH-17

**No rate limiting on authentication endpoints**

**Severity: HIGH**
**File:** `SecurityConfig.java`, `AuthController.java` — no rate limiting configured anywhere

`POST /api/auth/login` is `permitAll` with no request rate limit. An attacker can:

1. Enumerate known email addresses (e.g., from the `GET /api/users/{id}` endpoint which is also `permitAll`) and perform a credential-stuffing attack.
2. Execute a targeted brute-force attack against a specific account. BCrypt work factor 10 processes approximately 8–12 attempts per second per core; a single server can sustain a modest brute-force campaign indefinitely.
3. Mount a CPU exhaustion denial-of-service attack against `POST /api/auth/register`, since registration encodes a BCrypt hash per request.

The BCrypt constant-time dummy hash in `AuthService` prevents user enumeration via timing, but does not prevent password guessing.

**What should be done:** Add a servlet filter before the Spring Security filter chain that applies per-IP token-bucket rate limits:

| Endpoint | Limit |
|----------|-------|
| `POST /api/auth/login` | 5 per IP per minute; 20 per IP per hour |
| `POST /api/auth/register` | 3 per IP per hour |

Recommended library: Bucket4j 8.x (`bucket4j-core`, no Redis required for single-instance deployment). The `RateLimitFilter` should respond with `429 Too Many Requests` and a JSON `ErrorResponse` body matching the API error contract.

See also: `security-config-findings.md` F-12 for a complete implementation sketch.

---

### SH-18

**`app.cors.allowed-origins` default is `localhost:4200` — no production-safe default**

**Severity: LOW**
**File:** `application.properties`, line 26

```properties
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:4200}
```

The `SecurityConfig` constructor correctly validates that the value is not `*`. However, `http://localhost:4200` is the default for production deployments that forget to set `APP_CORS_ALLOWED_ORIGINS`. A production deployment without this environment variable would accept cross-origin credentialed requests from `http://localhost:4200` only — which is a localhost address and effectively means no legitimate cross-origin browser requests would succeed (since no production browser is at `localhost:4200`).

This is a **misconfiguration-by-omission** risk: the application appears to work in development (developer's browser is at `localhost:4200`) but silently breaks for all real users in production unless the operator remembers to set the environment variable.

**What should be done:** Make the default value explicitly broken in production by removing it or using an obviously invalid placeholder:

```properties
# No default — operator must explicitly set this. Example:
# APP_CORS_ALLOWED_ORIGINS=https://app.example.com
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS}
```

This causes the application to fail at startup (missing property) rather than running silently with a useless CORS policy. Alternatively, document the required environment variable prominently in the deployment guide.

---

### SH-19

**`PUT /api/users/{id}` relies solely on `@PreAuthorize` with no HTTP-layer rule**

**Severity: LOW**
**File:** `SecurityConfig.java` lines 93–103, `UserController.java` lines 38–42

```java
// SecurityConfig — no explicit rule for PUT /api/users/{id}
// Falls through to anyRequest().authenticated()

// UserController
@PreAuthorize("@ownership.isSelf(#id, principal)")
@PutMapping("/{id}")
public ResponseEntity<UserSummary> updateUser(@PathVariable UUID id, ...) {
```

`PUT /api/users/{id}` requires that the authenticated user is updating their own record. The access control is enforced by `@PreAuthorize("@ownership.isSelf(#id, principal)")` at the method level. This is correct. However, the HTTP-layer rule (`SecurityConfig.authorizeHttpRequests`) has no explicit entry for `PUT /api/users/**` — it falls through to `anyRequest().authenticated()`.

The consequence is that ownership enforcement happens only at the method layer. If `@EnableMethodSecurity` is ever removed, disabled, or if the annotation is accidentally removed from the controller method, the endpoint becomes accessible to any authenticated user with no HTTP-layer safety net.

**What should be done:** Add an explicit rule in `SecurityConfig` to complement the method-level guard:

```java
.requestMatchers(HttpMethod.PUT, "/api/users/{id}").authenticated()
```

This does not provide the ownership check (which requires the `id` variable and the `principal`), but it ensures the HTTP-layer always at minimum requires authentication, providing defense-in-depth against future accidental annotation removal.

---

### SH-20

**`Cache-Control` headers not verified for sensitive endpoints**

**Severity: LOW**
**File:** `SecurityConfig.java` (implicit Spring Security defaults)

Spring Security's default headers include `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` and `Pragma: no-cache`. These defaults apply to all requests that pass through the Spring Security filter chain.

**The risk:** Spring Boot's `WebMvcConfigurer` can override `Cache-Control` headers at the MVC layer (via `ResourceHandlerRegistry` or response entity headers). Specifically, file streaming responses in `DocumentController.streamFile()` return:

```java
return ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(sfr.contentType()))
    .contentLength(sfr.sizeBytes())
    .header(HttpHeaders.CONTENT_DISPOSITION, ...)
    .body(sfr.resource());
```

`ResponseEntity.ok()` builds a response without explicit `Cache-Control` headers. Whether Spring Security's default `Cache-Control: no-store` is applied depends on whether the `HeadersConfigurer.cacheControl()` is active for responses that carry a `Resource` body.

For documents served as files (`application/pdf`, `text/plain`, `application/epub+zip`), caching by the browser or a shared proxy is a privacy concern — a shared device or cached proxy could serve a private document to a later user.

**What should be done:** Explicitly set `Cache-Control: no-store, private` on all document file streaming responses:

```java
return ResponseEntity.ok()
    .cacheControl(org.springframework.http.CacheControl.noStore().cachePrivate())
    .contentType(MediaType.parseMediaType(sfr.contentType()))
    .contentLength(sfr.sizeBytes())
    .header(HttpHeaders.CONTENT_DISPOSITION, ...)
    .body(sfr.resource());
```

---

## CORS Configuration State Assessment

The current CORS configuration in full:

```java
// SecurityConfig.java lines 33–43 (constructor)
public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOriginsRaw) {
    this.allowedOrigins = Arrays.stream(allowedOriginsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    if (this.allowedOrigins.contains("*")) {
        throw new IllegalStateException(
                "APP_CORS_ALLOWED_ORIGINS must not be '*' when credentials are enabled. " +
                "Set explicit origins.");
    }
}

// SecurityConfig.java lines 63–73 (CORS bean)
CorsConfiguration config = new CorsConfiguration();
config.setAllowedOrigins(allowedOrigins);
config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
config.setAllowedHeaders(List.of("*"));
config.setAllowCredentials(true);
UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
source.registerCorsConfiguration("/api/**", config);
source.registerCorsConfiguration("/swagger-ui/**", config);
source.registerCorsConfiguration("/v3/api-docs/**", config);
```

**What is correct:**
- Origins are parsed from a comma-separated environment variable — multiple origins are supported.
- Wildcard origin `"*"` is blocked at startup via `IllegalStateException` — the application refuses to start if misconfigured.
- `allowedMethods` explicitly lists all HTTP methods including `PATCH` and `OPTIONS` — no method wildcard.
- `allowCredentials=true` is appropriate for a cookie-less JWT API where the frontend needs to include the `Authorization` header in cross-origin requests (required for credentialed CORS).
- CORS configuration is applied via `CorsConfigurationSource` (not `@CrossOrigin` annotations), which is the correct centralized approach.
- Swagger paths have explicit CORS entries, preventing "no CORS headers on preflight" failures for cross-origin Swagger usage.

**What is wrong (findings summary):**
- `allowedHeaders: ["*"]` — wildcard (SH-01)
- No `exposedHeaders` — `Location` header invisible to JS (SH-02)
- No `maxAge` — excessive preflights (SH-03)
- Credentials enabled for Swagger/docs paths unnecessarily (SH-04)
- No `OPTIONS` `permitAll` safety net (SH-05)

---

## CSRF Posture Assessment

CSRF is disabled:

```java
.csrf(AbstractHttpConfigurer::disable)
```

**Assessment: Correct.**

This API uses stateless JWT authentication delivered exclusively via the `Authorization: Bearer <token>` HTTP header. The `Authorization` header is a non-simple CORS header; browsers require an explicit `Access-Control-Allow-Headers` response to include it in cross-origin requests, and they never attach it automatically (unlike cookies). Therefore:

- A cross-site forged request from an attacker's page cannot include the victim's JWT.
- There is no session cookie to hijack.
- CSRF protection provides zero security benefit for this architecture.

Re-enabling CSRF for a stateless JWT API would add complexity (a CSRF token endpoint and double-submit cookie pattern) without improving security. The only correction needed is adding an explanatory comment (SH-06).

---

## Session Management Assessment

```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**Assessment: Correct.**

`STATELESS` prevents Spring Security from creating or using `HttpSession`. No `JSESSIONID` cookie is issued. The `JsonAuthenticationEntryPoint` writes the 401 response directly to the servlet output stream without calling `sendError()`, which avoids the known Spring Boot issue where `sendError()` can trigger a new session through the error-dispatch path.

---

## HTTP Security Headers Assessment

| Header | Status | Notes |
|--------|--------|-------|
| `Strict-Transport-Security` | Configured | Missing `preload` (SH-07) |
| `X-Content-Type-Options: nosniff` | Active (default) | Implicit; should be made explicit (SH-09) |
| `X-Frame-Options: DENY` | Active (default) | Implicit; should be made explicit (SH-09) |
| `X-XSS-Protection: 0` | Active (default) | Correct; modern guidance recommends disabled (0) over `1; mode=block` |
| `Cache-Control: no-cache, no-store` | Active (default) | Not applied to file streaming responses (SH-20) |
| `Content-Security-Policy` | Configured | `default-src 'none'` breaks Swagger UI (SH-08) |
| `Referrer-Policy: no-referrer` | Configured | Correct |
| `Permissions-Policy` | Absent | Low priority for a JSON API (SH-10) |
| `Cross-Origin-Resource-Policy` | Absent | (SH-11) |
| `Cross-Origin-Opener-Policy` | Absent | (SH-11) |

---

## Priority Fix Order

| Priority | Finding | Effort | Impact |
|----------|---------|--------|--------|
| 1 | SH-16 — Logout + token invalidation | Medium | HIGH |
| 2 | SH-17 — Rate limiting on auth endpoints | Medium | HIGH |
| 3 | SH-05 — Add `OPTIONS` `permitAll` rule | Trivial | HIGH |
| 4 | SH-08 — CSP breaks Swagger UI (gate Swagger on env flag) | Small | MEDIUM |
| 5 | SH-13 — Swagger `permitAll` in all environments | Small | MEDIUM |
| 6 | SH-09 — Make security headers explicit | Small | MEDIUM |
| 7 | SH-01 — Replace `allowedHeaders: ["*"]` with explicit list | Trivial | MEDIUM |
| 8 | SH-04 — Separate CORS config for Swagger paths (no credentials) | Small | MEDIUM |
| 9 | SH-14 — Restrict actuator endpoint exposure | Trivial | MEDIUM |
| 10 | SH-07 — Add HSTS `preload` | Trivial | LOW |
| 11 | SH-02 — Configure `exposedHeaders` | Trivial | LOW |
| 12 | SH-03 — Configure `maxAge` | Trivial | LOW |
| 13 | SH-15 — Sanitize exception message in filter | Trivial | LOW |
| 14 | SH-06 — Add CSRF disable comment | Trivial | LOW |
| 15 | SH-12 — Flush response in entry point | Trivial | LOW |
| 16 | SH-18 — Remove localhost default from CORS origins | Trivial | LOW |
| 17 | SH-20 — `Cache-Control: no-store` on file streaming | Trivial | LOW |
| 18 | SH-19 — Explicit HTTP-layer rule for `PUT /api/users/{id}` | Trivial | LOW |
| 19 | SH-10 — `Permissions-Policy` header | Small | LOW |
| 20 | SH-11 — CORP/COOP headers | Small | LOW |
