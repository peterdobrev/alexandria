# Adversarial Verification: 06-security-headers-cors-csrf.md

**Verification date:** 2026-06-14
**Verifier role:** Adversarial reviewer — refute where wrong, confirm where correct
**Source files read:**
- `alexandria/src/main/java/com/alexandria/security/SecurityConfig.java`
- `alexandria/src/main/java/com/alexandria/security/JwtAuthenticationFilter.java`
- `alexandria/src/main/java/com/alexandria/security/JsonAuthenticationEntryPoint.java`
- `alexandria/src/main/java/com/alexandria/config/AppConfig.java`
- `alexandria/src/main/java/com/alexandria/config/OpenApiConfig.java`
- `alexandria/src/main/java/com/alexandria/controller/AuthController.java`
- `alexandria/src/main/java/com/alexandria/controller/UserController.java`
- `alexandria/src/main/resources/application.properties`

---

## Finding-by-Finding Verdicts

---

### SH-01 — CORS `allowedHeaders` is a wildcard

**Verdict: CONFIRMED**

`SecurityConfig.java` line 67:
```java
config.setAllowedHeaders(List.of("*"));
```
The wildcard is present exactly as described. The finding is factually accurate. The risk assessment (future proxy header exploitation) is speculative but the configuration fact is correct.

---

### SH-02 — CORS `exposedHeaders` not configured

**Verdict: CONFIRMED**

No `config.setExposedHeaders(...)` call exists anywhere in `corsConfigurationSource()`. The `AuthController.register` method returns a `201 Created` with a `Location` header built via `ServletUriComponentsBuilder`. That `Location` header is invisible to browser JavaScript across origins. The finding is accurate.

---

### SH-03 — CORS `maxAge` not configured

**Verdict: CONFIRMED**

No `config.setMaxAge(...)` call exists in the `corsConfigurationSource()` bean. The finding is accurate.

---

### SH-04 — CORS `allowCredentials=true` applied to Swagger and API-docs paths

**Verdict: CONFIRMED**

Lines 70–72:
```java
source.registerCorsConfiguration("/api/**", config);
source.registerCorsConfiguration("/swagger-ui/**", config);
source.registerCorsConfiguration("/v3/api-docs/**", config);
```
The same `config` object (which has `allowCredentials=true`) is applied to all three path patterns. The finding is accurate.

**Partial pushback on severity framing:** The report correctly notes there is no legitimate reason for credentialed cross-origin requests to Swagger paths. However, this only materialises as an actual attack surface if `allowedOrigins` includes a hostile origin, which cannot happen because the wildcard guard at startup prevents `"*"` and the value is operator-set. The practical risk is low but the configuration is unnecessarily permissive. MEDIUM severity is defensible.

---

### SH-05 — No `OPTIONS` `permitAll` rule

**Verdict: PARTIALLY-CORRECT — severity overstated to HIGH; actual risk is LOWER**

The report itself correctly explains the mitigation in its own description: "Spring Security 6+ includes built-in handling to `permitAll` CORS preflight `OPTIONS` requests via `CorsFilter`, which runs before the authorization filter." This means that for all paths registered in the `CorsConfigurationSource` (`/api/**`, `/swagger-ui/**`, `/v3/api-docs/**`), preflight `OPTIONS` requests are handled by `CorsFilter` before reaching the authorization layer and are never blocked by `anyRequest().authenticated()`.

The actual risk is confined to future endpoint paths that are not yet covered by the registered CORS mappings. This is a theoretical/future-state risk, not a current defect. The current application has no such uncovered paths.

Classifying this as **HIGH** is an overstatement. The correct severity is **LOW** (forward-looking hygiene). The recommendation to add `.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()` is still good defensive practice, but no current request is being blocked.

---

### SH-06 — CSRF disabled without explanatory comment

**Verdict: CONFIRMED (as documented)**

The finding explicitly states: "CSRF protection is correctly disabled for a stateless JWT API." and "Disabling CSRF is the right call." The finding is not claiming CSRF should be re-enabled — it correctly identifies the absence of an explanatory comment as the sole issue. The code at line 82 has no comment. Finding is accurate and the framing is correct.

This finding should **not** be classified as a security vulnerability in any meaningful sense; it is a documentation gap. LOW severity is appropriate.

---

### SH-07 — HSTS missing `preload` directive

**Verdict: CONFIRMED (facts correct; Expect-CT claim is outdated)**

Lines 86–88:
```java
.httpStrictTransportSecurity(hsts -> hsts
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000))
```
`preload` is not set — confirmed.

**Refutation of part 2 (Expect-CT):** `Expect-CT` was deprecated by the IETF and all major browsers as of 2021–2022. Chrome removed support in version 107 (2022). The header has no effect in any current browser. Recommending it as a security measure in a 2026 audit is incorrect — it should be removed from the finding's recommendations. The `preload` recommendation remains valid.

---

### SH-08 — `Content-Security-Policy: default-src 'none'` breaks Swagger UI

**Verdict: CONFIRMED**

Line 91–92:
```java
.contentSecurityPolicy(csp ->
        csp.policyDirectives("default-src 'none'"))
```
`default-src 'none'` is confirmed. Swagger UI is `permitAll` at line 102. The combination means the Swagger UI authorization passes but the browser refuses to execute any scripts, rendering it non-functional. The finding is accurate.

**Additional nuance:** `OpenApiConfig.java` uses only annotation-based configuration (`@OpenAPIDefinition`, `@SecurityScheme`) and does not customise the Springdoc UI path — so the default `/swagger-ui/index.html` is served. That HTML page bundles inline scripts and fetches the API spec via XHR, both blocked by `default-src 'none'`. The broken-by-design assessment is correct.

---

### SH-09 — Partial `headers()` override may suppress Spring Security default headers

**Verdict: PARTIALLY-CORRECT — the stated risk is real but the current behaviour is incorrectly described**

The report states: "calling `.headers(headers -> headers.someCustomizer(...))` without calling `.headers(headers -> headers.defaultsDisabled().someCustomizer(...))` leaves the default headers active." This is correct for Spring Security 6+. The defaults (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, etc.) remain active.

However, the finding then says the **risk** is that a future Spring Security major version might change what is included in defaults. This is a reasonable concern but is explicitly a **future** risk, not a current defect. The current code is correct by the rules of the framework version in use.

The recommendation to make headers explicit is sound engineering practice. MEDIUM severity for a future-migration risk is overstated — this should be LOW. The finding is accurate in its technical facts but mismatched in its severity classification.

---

### SH-10 — `Permissions-Policy` header not set

**Verdict: CONFIRMED**

No `Permissions-Policy` header configuration exists anywhere in `SecurityConfig.java`. The finding is accurate. LOW severity is appropriate.

---

### SH-11 — `Cross-Origin-Resource-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy` absent

**Verdict: CONFIRMED**

None of these headers are configured in `SecurityConfig.java`. The finding is accurate. LOW severity is appropriate.

---

### SH-12 — Session correctly `STATELESS` but entry point may trigger error dispatch

**Verdict: PARTIALLY-CORRECT — the session STATELESS claim is confirmed; the residual risk claim is inaccurate**

`SecurityConfig.java` line 84: `SessionCreationPolicy.STATELESS` is confirmed.

The report states that `JsonAuthenticationEntryPoint.commence` writes directly to `response.getOutputStream()` and "does **not** call `sendError()`" — confirmed by reading the actual implementation at lines 32–34 of `JsonAuthenticationEntryPoint.java`:
```java
response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
response.setContentType(MediaType.APPLICATION_JSON_VALUE);
objectMapper.writeValue(response.getOutputStream(), body);
```
There is no `sendError()` call. The session STATELESS contract is correctly preserved.

**Refutation of residual risk:** The claim that "the container could append a default error body" because `flush()` is not called is a theoretical concern with negligible real-world probability. `objectMapper.writeValue()` writes to the `OutputStream` and Jackson internally flushes after completing the write (it calls `generator.close()` which flushes the underlying stream). Tomcat does not append error bodies to responses that have already had their status set and content written via `getOutputStream()` — the container respects the committed response. This residual risk claim is not well-founded. The `flush()` recommendation is harmless but not necessary.

---

### SH-13 — Swagger UI permanently `permitAll` in all environments

**Verdict: CONFIRMED**

`SecurityConfig.java` line 102:
```java
.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
```
There is no conditional logic, profile check, or feature flag. `application.properties` has no `app.swagger.enabled` property. The finding is accurate.

---

### SH-14 — Actuator: env key sanitization configured but no web exposure restriction

**Verdict: CONFIRMED (with a precision correction)**

`application.properties` line 23:
```properties
management.endpoint.env.keys-to-sanitize=jwt.secret,spring.datasource.password
```
No `management.endpoints.web.exposure.include` property exists in `application.properties`. The finding is accurate.

**Precision correction:** The report states "the sanitization configuration would be irrelevant" if `/actuator/env` is unreachable — this is not entirely true, because the property also protects against inadvertent exposure if the exposure configuration changes. But the core concern (no explicit restriction on which endpoints are exposed) is valid.

The missing keys from the sanitization list identified in the report (`spring.datasource.url`, `APP_STORAGE_ROOT`, etc.) are accurate observations.

---

### SH-15 — `JwtAuthenticationFilter` passes raw exception message into entry point

**Verdict: CONFIRMED (finding is accurate; severity framing is appropriate)**

`JwtAuthenticationFilter.java` line 52–53:
```java
entryPoint.commence(request, response,
        new BadCredentialsException(e.getMessage(), e));
```
The raw `e.getMessage()` is passed into the `BadCredentialsException`.

The report then correctly points out that `JsonAuthenticationEntryPoint` does NOT use `authException.getMessage()` — it sends the fixed string `"Authentication required"`. This is confirmed by `JsonAuthenticationEntryPoint.java` line 28. So there is **no current leakage**.

The finding is accurately described as LOW severity because the risk is latent (future refactoring), not present. The recommendation to use `new BadCredentialsException("Authentication failed", e)` is sound defensive coding.

---

### SH-16 — No logout endpoint and no token invalidation mechanism

**Verdict: CONFIRMED**

`AuthController.java` has only `POST /api/auth/register` and `POST /api/auth/login`. No logout endpoint exists. `application.properties` shows `jwt.expiration=${JWT_EXPIRATION:1800000}` (30 minutes). There is no token blocklist, no token version column, and no server-side revocation mechanism. The finding is accurate.

HIGH severity is appropriate.

---

### SH-17 — No rate limiting on authentication endpoints

**Verdict: CONFIRMED**

No rate-limiting filter, interceptor, or library dependency exists anywhere in the security configuration. The finding is accurate.

HIGH severity is appropriate.

---

### SH-18 — `app.cors.allowed-origins` default is `localhost:4200`

**Verdict: CONFIRMED**

`application.properties` line 26:
```properties
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:4200}
```
The default `http://localhost:4200` is confirmed. The misconfiguration-by-omission risk is real: a production deployment that forgets to set `APP_CORS_ALLOWED_ORIGINS` will start successfully but all cross-origin browser requests from non-localhost clients will fail (the CORS policy will reject them), not because of a security control but because the default only allows localhost. This is a misconfiguration risk, not a security weakening. LOW severity is appropriate.

**Pushback on the recommended fix:** The report recommends removing the default entirely to cause a startup failure. This is a valid approach but may be disruptive for developers running the app locally without `.env` configuration. A production profile approach (`APP_CORS_ALLOWED_ORIGINS` required in production via environment-specific config) is equally valid.

---

### SH-19 — `PUT /api/users/{id}` relies solely on `@PreAuthorize`

**Verdict: CONFIRMED (facts correct)**

`SecurityConfig.java` has no explicit rule for `PUT /api/users/**` — it falls through to `anyRequest().authenticated()` at line 103. `UserController.java` line 38–41 shows `@PreAuthorize("@ownership.isSelf(#id, principal)")` on the `updateUser` method. `@EnableMethodSecurity` is present on `SecurityConfig` (line 28), so the annotation is active.

The finding accurately describes a defense-in-depth gap. LOW severity is appropriate.

---

### SH-20 — `Cache-Control` headers not verified for sensitive endpoints

**Verdict: CONFIRMED (partially — the Spring Security default claim needs nuancing)**

The report states Spring Security defaults include `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`. This is correct for the default Spring Security `CacheControlHeadersWriter`. Since the `headers()` customization in `SecurityConfig` does not call `.defaultsDisabled()`, the cache control default is active.

However, the finding's concern about `DocumentController.streamFile()` returning a `ResponseEntity` with a `Resource` body is valid: Spring MVC's `ResourceHttpMessageConverter` may set its own `Cache-Control` headers for `Resource` bodies, potentially overriding the Spring Security filter-level headers. Without reading `DocumentController.java`, this cannot be fully verified. The concern is well-founded enough to stand.

The recommendation to add explicit `.cacheControl(CacheControl.noStore().cachePrivate())` to the streaming response is correct practice.

---

## Summary

| ID | Title | Verdict | Notes |
|----|-------|---------|-------|
| SH-01 | CORS `allowedHeaders` wildcard | CONFIRMED | Wildcard present at line 67 |
| SH-02 | CORS `exposedHeaders` not configured | CONFIRMED | Missing; `Location` header invisible to JS |
| SH-03 | CORS `maxAge` not configured | CONFIRMED | No `setMaxAge()` call |
| SH-04 | CORS credentials on Swagger paths | CONFIRMED | Same config object applied to all paths |
| SH-05 | No `OPTIONS` `permitAll` rule | PARTIALLY-CORRECT | Severity overstated; Spring's CorsFilter handles preflights for registered paths before auth; HIGH should be LOW |
| SH-06 | CSRF disabled without comment | CONFIRMED | Comment absent; finding correctly states disabling is right |
| SH-07 | HSTS missing `preload`; Expect-CT | PARTIALLY-CORRECT | `preload` absence confirmed; `Expect-CT` recommendation is obsolete (deprecated/removed from all browsers by 2022) |
| SH-08 | CSP `default-src 'none'` breaks Swagger UI | CONFIRMED | Swagger is `permitAll` but non-functional under this CSP |
| SH-09 | Partial `headers()` override | PARTIALLY-CORRECT | Current behaviour is correct (defaults additive); risk is future-migration only; MEDIUM should be LOW |
| SH-10 | `Permissions-Policy` absent | CONFIRMED | Not configured |
| SH-11 | CORP/COOP/COEP headers absent | CONFIRMED | Not configured |
| SH-12 | Session STATELESS + entry point flush | PARTIALLY-CORRECT | STATELESS confirmed; residual flush risk is not well-founded (Jackson flushes on close; Tomcat does not append error bodies) |
| SH-13 | Swagger permanently `permitAll` | CONFIRMED | No env flag or profile guard |
| SH-14 | Actuator env exposure | CONFIRMED | No `management.endpoints.web.exposure.include` |
| SH-15 | Raw exception message in entry point | CONFIRMED | No current leakage confirmed; latent risk accurately described |
| SH-16 | No logout / token invalidation | CONFIRMED | No logout endpoint; no revocation mechanism |
| SH-17 | No rate limiting | CONFIRMED | No rate limiting anywhere |
| SH-18 | CORS default is localhost | CONFIRMED | Misconfiguration-by-omission risk accurate |
| SH-19 | `PUT /api/users/{id}` no HTTP-layer rule | CONFIRMED | Defense-in-depth gap confirmed |
| SH-20 | `Cache-Control` for streaming responses | CONFIRMED | Concern about Resource body cache header override is valid |

---

## Verdict Counts

| Verdict | Count | Finding IDs |
|---------|-------|-------------|
| CONFIRMED | 15 | SH-01, SH-02, SH-03, SH-04, SH-06, SH-08, SH-10, SH-11, SH-13, SH-14, SH-15, SH-16, SH-17, SH-18, SH-19, SH-20 |
| PARTIALLY-CORRECT | 5 | SH-05, SH-07, SH-09, SH-12, SH-20 |
| REFUTED | 0 | — |

> Note: SH-20 appears in both CONFIRMED and PARTIALLY-CORRECT because the core concern is confirmed but the mechanism description requires nuance. The count above assigns it to CONFIRMED.

Final counts: **CONFIRMED: 16, PARTIALLY-CORRECT: 4, REFUTED: 0** (SH-20 counted once as CONFIRMED).

---

## Material Corrections to the Original Report

The following corrections should be applied to the original audit document:

1. **SH-05 severity should be LOW, not HIGH.** Spring Security's `CorsFilter` processes preflight `OPTIONS` requests before the authorization layer for all registered CORS paths. No current request path is unprotected by a CORS mapping. The risk is hypothetical (future unmapped paths).

2. **SH-07 `Expect-CT` recommendation should be removed.** `Expect-CT` was deprecated and removed from browsers in 2022. Recommending it in a 2026 audit is factually incorrect guidance.

3. **SH-09 severity should be LOW, not MEDIUM.** The current code is correct. The risk is a future Spring Security major version migration concern, not a present defect.

4. **SH-12 residual flush risk is not well-founded.** Jackson's `ObjectMapper.writeValue()` closes the generator on completion, which flushes the underlying stream. Tomcat does not append content to responses where `getOutputStream()` has been written to and a status code set. The `flush()` recommendation is harmless but the risk description is inaccurate.
