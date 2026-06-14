Now I have a complete picture of the codebase. Let me compile the full audit findings.

---

# Alexandria API — Error Handling & Information Leakage Audit

---

## Finding 1 — `InvalidTokenException` falls to the generic 500 handler if thrown from application code

**Severity: Medium**

**Location:** `GlobalExceptionHandler.java` (no handler for `InvalidTokenException`); `JwtService.java` lines 24–26, 47–49; `JwtAuthenticationFilter.java` line 46.

**Detail:** `InvalidTokenException` is thrown in two places inside `JwtService`:

1. In the constructor, when the configured secret key is too weak (`Keys.hmacShaKeyFor` throws `WeakKeyException`). This is a startup-time scenario but the constructor is called as part of Spring bean wiring — if it throws, the application context fails to start, so this never reaches the handler at all.
2. In `extractClaims()`, wrapping a `JwtException` from the JWT parser.

The `JwtAuthenticationFilter` explicitly catches `InvalidTokenException` (line 46) and returns a bare 401 `sendError` (no JSON body), so the normal token-rejection path is correctly handled. However, `InvalidTokenException` is a plain `RuntimeException` with no dedicated handler in `GlobalExceptionHandler`. If any future application code (e.g., a service layer that calls `jwtService.extractClaims()` directly) throws it outside the filter, it falls through to the generic `Exception` handler and returns a 500 with the generic "An unexpected error occurred" message.

**What is exposed to the client:** Only the generic 500 message, which is safe. However, the generic handler logs `ex.getMessage()` at ERROR level including the full stack trace (line 200). `InvalidTokenException` messages are: "JWT token is invalid or expired" or "JWT secret key is too weak". The latter, if somehow reachable, would disclose internal configuration details in logs.

**Recommendation:** Add an explicit `@ExceptionHandler(InvalidTokenException.class)` that returns 401 and a fixed "Invalid or expired token" message, making the intent explicit and preventing any accidental wrong-status future path.

---

## Finding 2 — `UsernameNotFoundException` from `SecurityUtils.getCurrentUser()` produces a 500

**Severity: High**

**Location:** `SecurityUtils.java` lines 17–22; `GlobalExceptionHandler.java` (no handler); `CommentController.java` line 49; `DocumentController.java` lines 78, 88.

**Detail:** `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException` in two cases:

1. `authentication == null` — message: `"No authenticated user"`. This can only occur on endpoints where Spring Security does not enforce authentication and a request arrives without a token. For `addComment` (POST `/api/documents/{id}/comments`), there is no `@PreAuthorize` on the controller method and no `authenticated()` rule in `SecurityConfig` for POST to that path. The SecurityConfig only has `anyRequest().authenticated()` as the final fallback, which does cover this path — so Spring Security's `authenticationEntryPoint` fires before the controller is reached, returning a plain 401 `sendError`. In practice this path is blocked by the filter chain.
2. User was found in the JWT but deleted from the database between token issuance and this call — message: `"User not found: {email}"`. Because `JwtAuthenticationFilter.setAuthentication()` already called `userDetailsService.loadUserByUsername()` successfully (or this would have been caught at the filter), this second path in `SecurityUtils` is a race condition. It requires the user to be deleted between the filter completing and the service method running, making it unlikely but not impossible.

Neither `UsernameNotFoundException` nor its parent `AuthenticationException` is handled in `GlobalExceptionHandler`. Spring Security's `AccessDeniedException` handler is present but `UsernameNotFoundException` is a different hierarchy (`AuthenticationException` → `UsernameNotFoundException`). This means the exception falls to the generic `Exception` handler and returns HTTP 500 with `"An unexpected error occurred"`.

**What is exposed to the client:** The 500 message is generic and safe. However, the logs receive `"User not found: alice@example.com"` at ERROR level — the deleted user's email is disclosed in the error log.

**Recommendation:** Add `@ExceptionHandler(UsernameNotFoundException.class)` returning 401 with a fixed message such as "Authentication required". This also avoids the misleading 500 status for what is semantically an authentication failure.

---

## Finding 3 — Generic `Exception` handler logs `ex.getMessage()` — sensitive data possible

**Severity: Medium**

**Location:** `GlobalExceptionHandler.java` lines 199–202.

```java
log.error("Unexpected error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
```

**Detail:** The message is logged (not returned to the client). The concern is the variety of exceptions that can reach this handler. Examples in this codebase:

- `IllegalStateException` from `AuthService.register()` line 52: `"Default role USER not configured"` — exposes internal role configuration details in logs.
- `UncheckedIOException` from `LocalFileStorageService.store()` line 71: `"Failed to store uploaded file"` — benign, but the wrapped `IOException` message (the third argument, the full exception with stack trace) could include filesystem paths like `/var/data/uploads/ab/cd/...`.
- Any `NullPointerException` or `DataAccessException` message from JPA could include table names, column names, or SQL fragments.
- `UsernameNotFoundException` (see Finding 2) includes the email address.

The client only ever sees `"An unexpected error occurred"`, so there is no direct leakage to callers. The issue is log-level leakage, which matters if logs are exported to a SIEM, third-party log aggregator, or are accessible to operators who should not see user emails.

**Recommendation:** Acceptable as-is for most deployments. If stricter log hygiene is required, consider logging only the exception class name and a sanitised summary rather than the raw `getMessage()` for the catch-all handler. At minimum, ensure the log aggregation pipeline has appropriate access controls.

---

## Finding 4 — `IllegalArgumentException` handler returns `ex.getMessage()` verbatim in the response body

**Severity: Medium**

**Location:** `GlobalExceptionHandler.java` lines 183–188; `DocumentController.java` lines 129–137; `LocalFileStorageService.java` lines 33–44.

**Detail:** `IllegalArgumentException.getMessage()` is returned directly to the client as the `message` field of the `ErrorResponse`. The known messages in this codebase are:

- `DocumentController.validateSort()`: `"Sort field not allowed: {order.getProperty()}"` — exposes the sort parameter name the caller submitted, which is the caller's own input, so not a server-secret leak.
- `LocalFileStorageService.store()` via `InvalidDocumentContentException` (which extends `IllegalArgumentException`):
  - `"Uploaded file is empty"` — benign.
  - `"Unsupported content type: {contentType}"` — echoes the caller's own `Content-Type` header value back. Fine.
  - `"Resolved path escapes storage root"` — this is a **path traversal attack attempt** message. Returning this to the client confirms that the server detected a path traversal attempt and that the storage abstraction uses a root-based containment check. An attacker learns the server uses path containment and that their traversal was detected. The message is also emitted from `load()` (no client-visible path there), but from `store()` this is returned as a 400 body. Consider a generic "Invalid file" message instead.

There is also a latent risk: any library or Spring Data code that throws `IllegalArgumentException` (e.g., a Pageable constructor receiving an out-of-range page size, or Jackson's binding code) will have its raw message forwarded to the client. Library exception messages often contain internal field names, class names, or type descriptors.

**Recommendation:** Replace the `IllegalArgumentException` handler to return a fixed, generic message for cases where the source is not under application control. Application-defined subclasses like `InvalidDocumentContentException` should have their own handler returning a curated message. Specifically, do not surface the path-traversal detection message.

---

## Finding 5 — `MethodArgumentTypeMismatchException` exposes `ex.getValue()` and `ex.getName()` in the response

**Severity: Low**

**Location:** `GlobalExceptionHandler.java` lines 67–73.

```java
String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
```

**Detail:** `ex.getName()` is the parameter name as declared in the controller method signature (e.g., `"id"`, `"categoryId"`, `"authorId"`). `ex.getValue()` is the raw string the client submitted. Both are derived from the client's own request, not from server internals. There is no server-side secret disclosure here.

The parameter names are part of the public API contract (visible in OpenAPI docs) and echo back only what the caller sent. This is standard REST API practice for developer-friendly error messages.

**Verdict:** This is not an information leakage issue. The message is appropriate and helpful for API consumers. No change recommended.

---

## Finding 6 — `AuthService.register()` logs the email address on duplicate registration attempts

**Severity: Low**

**Location:** `AuthService.java` line 61.

```java
log.warn("Registration attempt with already-used email: {}", request.email());
```

**Detail:** Logging email addresses on business-logic events (duplicate registration, successful login) is common practice and is necessary for abuse investigation and audit trails. The email is already known to the submitter; it is their own data.

However, there are privacy concerns in GDPR-regulated deployments. Logging PII such as email addresses means the log store becomes a data processing record. Log retention and access must comply with data protection requirements. If the registration endpoint is publicly accessible (it is, per `SecurityConfig`), this log line is reachable by anyone, and under a high-volume enumeration attack would result in large volumes of user emails being written to the log.

**Verdict:** Acceptable for most internal deployments. For public-facing production, consider logging a hash of the email (e.g., SHA-256 hex of lowercase email) instead of the plaintext, and review log retention policy. The line on 66 (`"User registered successfully: {}"`) has the same characteristic.

---

## Finding 7 — `AuthService.login()` logs the attempted email on failure

**Severity: Low**

**Location:** `AuthService.java` line 77.

```java
log.warn("Failed login attempt for email: {}", request.email());
```

**Detail:** Same category as Finding 6. Logging the attempted email on failure is necessary for security monitoring (brute-force detection). However, if an attacker submits thousands of login attempts with arbitrary emails, those emails accumulate in logs. For the majority of deployments this is the correct and intended behaviour — you want to be able to trace abuse attempts.

Note: the HTTP response is deliberately generic ("Invalid credentials") and the timing side-channel defence (BCrypt dummy hash) is correctly implemented. The log line does not affect the client-visible response and is not exploitable for enumeration via the response.

**Verdict:** Appropriate security audit logging. No change recommended for the API response. Review log access controls and retention as with Finding 6.

---

## Finding 8 — `JwtAuthenticationFilter` logs the authenticated email at DEBUG level

**Severity: Info**

**Location:** `JwtAuthenticationFilter.java` line 44.

```java
log.debug("Authenticated user: {}", email);
```

**Detail:** This is DEBUG level only. In production, DEBUG logging is typically disabled, so this does not appear in production logs. If DEBUG is enabled in production (which it should not be), every authenticated request would emit a log line containing the user's email address. This is a misconfiguration risk, not a code defect.

`UserDetailsServiceImpl` also logs `"Loading user by username: {}"` and `"User not found for username: {}"` at DEBUG and WARN respectively, with the same characteristic.

**Verdict:** Acceptable at DEBUG level. Document that DEBUG must not be enabled on the security package in production.

---

## Finding 9 — `AuthResponse` returns a raw JWT in the response body; the JWT payload contains the user's email

**Severity: Low / Info**

**Location:** `AuthResponse.java`; `JwtService.generateToken()` line 32 (`subject(user.getEmail())`).

**Detail:** The JWT subject (`sub` claim) is set to the user's email address. The JWT payload is base64-encoded (not encrypted), so any party that receives the token can decode and read the email without any key material. The email is therefore embedded in the token returned to the client on both `/api/auth/register` and `/api/auth/login`.

This is a design decision, not a bug. The implications are:

1. If the token is logged (e.g., in an access log as part of the Authorization header), the email is extractable from the log.
2. If the token is stored in browser `localStorage`, XSS can extract the email.
3. The `AuthResponse` has no `token_type` field (only `token`), no `expires_in`, and no expiry hint. Clients cannot determine token expiry without decoding the JWT, which is unexpected for a public API.

**Recommendation:** Consider using the user's opaque UUID as the JWT subject instead of the email. This prevents email extraction from the token and avoids issues if an account changes email. Add `expiresIn` (seconds) to `AuthResponse` for client convenience.

---

## Finding 10 — `EmailAlreadyInUseException` confirms email existence to unauthenticated callers (email enumeration)

**Severity: High**

**Location:** `EmailAlreadyInUseException.java` line 6; `GlobalExceptionHandler.java` lines 147–152 (ConflictException handler returns `ex.getMessage()`).

```java
super("Email already in use: " + email, "EMAIL_TAKEN");
```

The handler returns the full message in the 409 response body:

```json
{ "status": 409, "error": "Conflict", "message": "Email already in use: alice@example.com", ... }
```

**Detail:** The `/api/auth/register` endpoint is publicly accessible (`permitAll`). An attacker can submit registration requests for arbitrary email addresses and determine which ones already have accounts based on the 409 response. The response body further echoes the email back. This is a textbook email enumeration oracle.

The timing side-channel in `login()` (Finding 7 area) was carefully neutralised with the dummy BCrypt hash. The same care was not applied here: a 201 (new account created) vs 409 (email taken) is an unambiguous enumeration signal.

**Recommendation:**
1. Replace the 409 response message with a fixed string such as `"Registration failed"` or `"An account with this email already exists"` (the latter is common UX practice and not materially worse than the current message, but omitting the email from the body stops echoing PII).
2. Consider returning 200 with a generic `"If this email is not registered, an account has been created"` message (OWASP recommendation for registration). Whether this is appropriate depends on the product's UX requirements.
3. At minimum, remove the email from `EmailAlreadyInUseException`'s message string — the exception message should not contain PII since it is also logged.

---

## Finding 11 — `ForbiddenException` is abstract; only `AccessForbiddenException` extends it; the handler mapping is correct

**Severity: Info**

**Location:** `ForbiddenException.java`; `AccessForbiddenException.java`; `GlobalExceptionHandler.java` lines 140–145.

**Detail:** `ForbiddenException` is `abstract`. The only concrete subclass in the codebase is `AccessForbiddenException`. The handler is annotated with `@ExceptionHandler(ForbiddenException.class)`, which matches all subclass instances via polymorphism. Spring's `@ExceptionHandler` uses `instanceof` matching, so `AccessForbiddenException` is caught correctly.

The handler returns the exception's own `getMessage()` in the response. `AccessForbiddenException` messages in the codebase are:
- `"Access denied"` (from `CommentService.assertVisible()`) — generic, appropriate.

There is also `AccessDeniedException` (Spring Security's own class) handled separately with a hardcoded `"Access denied"` message. There is no confusion between these two. The two 403 paths (application-level `ForbiddenException` and security-level `AccessDeniedException`) are both handled correctly.

**Note:** The `errorCode` field in `ForbiddenException` (and its subclass) is populated and accessible via `getErrorCode()`, but it is not included in `ErrorResponse`. Clients cannot distinguish between access denied reasons by code. This may or may not be intentional.

---

## Finding 12 — `ReadingListItemAlreadyExistsException` exposes both list UUID and document UUID in the 409 body

**Severity: Low**

**Location:** `ReadingListItemAlreadyExistsException.java` lines 7–9.

```java
super("Document " + documentId + " is already in reading list " + listId, "READING_LIST_ITEM_ALREADY_EXISTS");
```

The `ConflictException` handler returns `ex.getMessage()` verbatim.

**Detail:** The caller already knows both the `listId` (it is in the URL path) and the `documentId` (it is in the request body). The message does not expose any server-internal data. There is no meaningful information leakage here beyond confirming the precondition the caller explicitly triggered.

However, the message style is inconsistent: it uses UUID values rather than a human-readable description. For a public API this is fine; for a consumer-facing API, something like `"This document is already in your reading list"` would read better.

**Verdict:** No security issue. Cosmetic inconsistency only.

---

## Finding 13 — `DocumentNotFoundException` exposes the requested UUID in the 404 response

**Severity: Low**

**Location:** `DocumentNotFoundException.java` lines 6–8.

```java
super("Document not found: " + id, "DOCUMENT_NOT_FOUND");
```

**Detail:** The UUID is the caller's own input (from the URL path variable). It does not expose any server-internal data. However, there is a deliberate security design in `DocumentService.get()` and `DocumentService.streamFile()`: when a document is PRIVATE and the caller is not the owner, the service throws `DocumentNotFoundException(id)` rather than an access-denied exception. This intentionally makes PRIVATE documents indistinguishable from non-existent ones — a correct security-through-obscurity pattern.

The 404 message `"Document not found: {uuid}"` echoes the UUID. Since the UUID was in the request, the caller already knows it. There is no enumeration risk here: UUIDs are random (not sequential), so a 404 response does not help an attacker enumerate valid IDs.

**Verdict:** Acceptable. The 404-masking of PRIVATE documents is correct. Echoing the UUID is benign given UUID randomness.

**Note for completeness:** `CategoryNotFoundException`, `CommentNotFoundException`, `ReadingListNotFoundException`, `UserNotFoundException`, and `ReadingListItemNotFoundException` all echo the UUID(s) in their messages in the same pattern. All are equivalent — no risk beyond the caller's own input.

---

## Finding 14 — `HttpMediaTypeNotAcceptableException` returns `"Not acceptable"` with no `Accept` hint

**Severity: Info**

**Location:** `GlobalExceptionHandler.java` lines 119–124.

**Detail:** Unlike `HttpMediaTypeNotSupportedException` (415 handler), which correctly adds an `Accept` response header listing supported types, the 406 handler returns only `"Not acceptable"` with no header indicating what media types the server can produce. This is technically permitted by RFC 7231 (the server does not have to list alternatives if none are acceptable), but it makes API debugging harder.

No information is leaked. The suppression of `ex.getMessage()` from the response is correct — Spring's internal message for this exception can contain the full list of registered message converters, which would expose implementation details.

**Recommendation:** Consider adding the producible media types to the response body or as an `Accept` header for developer convenience, without including Spring-internal message converter names.

---

## Finding 15 — Stack trace leakage to clients

**Severity: Info (confirmed absent)**

**Location:** `GlobalExceptionHandler.java` — all handlers; Spring Boot `server.error.include-stacktrace` default.

**Detail:** No handler in `GlobalExceptionHandler` includes stack trace output in any response body. All responses use the structured `ErrorResponse` record with five fixed fields (`status`, `error`, `message`, `timestamp`, `path`). Spring Boot's default `server.error.include-stacktrace=never` means the `/error` fallback endpoint also does not include stack traces by default.

The generic handler logs the full stack trace via `log.error(..., ex)` (line 200), which is correct server-side behaviour.

**Verdict:** No stack trace is leaked to clients. Confirmed safe.

---

## Finding 16 — CORS violation response

**Severity: Info**

**Location:** `SecurityConfig.java` lines 48–57, 65; Spring Security CORS processing.

**Detail:** When a request violates the CORS policy (e.g., origin not in `allowedOrigins`, or a non-CORS request to a CORS-enabled endpoint), Spring Security's `CorsFilter` (or Spring MVC's CORS interceptor) responds with HTTP 403 with no body. The CORS configuration covers only `/api/**` routes. There is no custom CORS error response body; the client receives an empty 403.

No application data is included in CORS rejection responses. The browser's same-origin policy prevents the JavaScript making the request from reading the response body anyway, so even if a body were present, it would be inaccessible cross-origin.

The `allowCredentials(true)` with `setAllowedOrigins(List.of(allowedOrigins))` is correct — Spring Security rejects wildcard origins when credentials are enabled. The configuration reads the allowed origin from `@Value("${app.cors.allowed-origins}")`, but this is a single string. If multiple origins are required, only one is accepted (the whole string is treated as a single origin value).

**Verdict:** No information leakage in CORS error paths. Note the single-origin limitation for future reference.

---

## Finding 17 — `MultipartException` handler includes raw truncated exception message in the response

**Severity: Medium**

**Location:** `GlobalExceptionHandler.java` lines 168–181.

```java
String detail = ex.getMessage();
String message = "Invalid multipart request";
if (detail != null && !detail.isBlank()) {
    String truncated = detail.length() > MESSAGE_TRUNCATE_LIMIT
            ? detail.substring(0, MESSAGE_TRUNCATE_LIMIT)
            : detail;
    message = message + ": " + truncated;
}
```

**Detail:** The raw exception message from `MultipartException` (a Spring/Servlet container exception) is appended to the response body, truncated to 200 characters. The content of `MultipartException.getMessage()` is determined by the servlet container (Tomcat/Jetty/Undertow) and the multipart parsing library. In practice, these messages can include:

- File system temporary storage paths: `"Could not parse multipart request; nested exception is java.io.IOException: /tmp/tomcat.xyz/work/..."`
- Internal class names and stack information in abbreviated form.
- The original filename as submitted by the client (reflecting caller input back).

Even truncated to 200 characters, this is enough to expose a filesystem path segment or an internal class name. The `MaxUploadSizeExceededException` handler (lines 161–166) correctly uses a fixed message `"Upload size exceeds the allowed limit"` with no raw message forwarding — the `MultipartException` handler should follow the same pattern.

**Recommendation:** Replace the handler body with a fixed message and do not include `ex.getMessage()` in the response at all. Log the full message server-side (which already happens on line 179) and return only `"Invalid multipart request"` to the client.

---

## Finding 18 — `InvalidDocumentContentException` (extends `IllegalArgumentException`) reveals path-traversal detection

**Severity: Medium** (cross-reference with Finding 4)

**Location:** `LocalFileStorageService.java` lines 59–61; `GlobalExceptionHandler.java` `IllegalArgumentException` handler.

**Detail:** This was partially covered in Finding 4 but warrants its own entry. When `LocalFileStorageService.store()` detects that the resolved file path escapes the storage root, it throws:

```java
throw new InvalidDocumentContentException("Resolved path escapes storage root");
```

This bubbles up as an `IllegalArgumentException` and the handler returns the raw message in the 400 response. The client learns:
1. The server has a storage root.
2. Path containment checking is in use.
3. Their filename caused a path escape detection.

This is defence-in-depth leakage: an attacker probing path traversal now has confirmation that the traversal attempt was caught. While this does not enable an attack by itself, it is unnecessary information. The filename sanitisation in `sanitizeFilename()` should already prevent this, so this code path represents an internal assertion failure, not expected user error.

**Recommendation:** Return `"Invalid file"` or the same generic `"Uploaded file is empty"` level of detail. The specific detection message should be logged at WARN/ERROR but not echoed to the client.

---

## Finding 19 — `addComment` endpoint lacks controller-level authentication enforcement

**Severity: Medium**

**Location:** `CommentController.java` lines 44–49; `SecurityConfig.java` (no explicit rule for POST to `/api/documents/*/comments`).

**Detail:** The `addComment` POST handler has no `@PreAuthorize` annotation. `SecurityConfig` has `anyRequest().authenticated()` as the final rule, which does cover this endpoint. However, this is a rely-on-catch-all pattern: if the security configuration were refactored to list rules exhaustively (removing the `anyRequest` fallback), this endpoint would become inadvertently public.

Additionally, if an unauthenticated request does somehow reach `CommentController.addComment()`, `securityUtils.getCurrentUser()` throws `UsernameNotFoundException("No authenticated user")` (from `SecurityUtils.java` line 17), which falls to the generic 500 handler (see Finding 2) — returning a confusing 500 where 401 is expected.

**Recommendation:** Add `@PreAuthorize("isAuthenticated()")` to `addComment` to make the intent explicit and independent of the fallback rule. This also ensures a consistent 403 (from Spring Security method security) rather than a 500 from the service layer.

---

## Finding 20 — `SecurityUtils.getCurrentUser()` message leaks email in `UsernameNotFoundException`

**Severity: Medium** (cross-reference with Finding 2)

**Location:** `SecurityUtils.java` line 21.

```java
.orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
```

**Detail:** The email from the security context is embedded in the exception message. When this exception reaches the generic handler, the log entry (Finding 3 / Finding 2) contains the email. Additionally, `UserDetailsServiceImpl.loadUserByUsername()` has the same pattern at line 39: `"User not found: " + username`.

The `UsernameNotFoundException` is caught in `JwtAuthenticationFilter` (line 46) and the message is logged as a warning (line 47) before returning a 401 with no body — so the email does reach the access log in that normal path.

**Recommendation:** Use a fixed `"Authentication failed"` message for `UsernameNotFoundException` instances that might reach logs, or log only a hash of the email for traceability without PII exposure.

---

## Summary Table

| # | Finding | Severity | Client Exposed | Log Exposed |
|---|---------|----------|----------------|-------------|
| 1 | `InvalidTokenException` unhandled → falls to 500 | Medium | Generic 500 only | JWT error details |
| 2 | `UsernameNotFoundException` unhandled → 500 | High | Generic 500 | User email |
| 3 | Generic handler logs `ex.getMessage()` | Medium | None | Internal details, emails |
| 4 | `IllegalArgumentException` returns raw message | Medium | Implementation details | Same |
| 17 | `MultipartException` includes raw servlet message | Medium | Filesystem paths, class names | Same |
| 18 | Path-traversal detection message in 400 response | Medium | Attack detection confirmation | Same |
| 19 | `addComment` lacks explicit auth annotation | Medium | 500 instead of 401/403 | None |
| 20 | `UsernameNotFoundException` message contains email | Medium | None | User email |
| 10 | `EmailAlreadyInUseException` — email enumeration oracle | High | User email, account existence | User email |
| 6 | Auth logging writes emails to logs | Low | None | User email (PII) |
| 7 | Login failure logging writes attempted emails | Low | None | Attempted emails (PII) |
| 8 | JWT filter logs email at DEBUG | Info | None | Email (DEBUG only) |
| 9 | JWT subject is email; base64-decodable from token | Low | User email (in token) | N/A |
| 11 | `ForbiddenException` hierarchy correct | Info | None | None |
| 12 | `ReadingListItemAlreadyExistsException` echoes UUIDs | Low | Caller's own input | None |
| 13 | `DocumentNotFoundException` echoes UUID | Low | Caller's own input | None |
| 14 | 406 handler returns no `Accept` hint | Info | None | None |
| 15 | Stack traces — confirmed absent from responses | Info | None | Logs only (correct) |
| 16 | CORS violations return empty 403 | Info | None | None |
