I now have the complete picture. Here is the implementation plan.

---

# Alexandria API — Error Handling & Information Leakage: Implementation Plan

## 1. Executive Summary

**Wrong HTTP status codes (500 where 401/403 is correct)**

Two unhandled exception types fall through to the generic `Exception` handler and return 500:

- `UsernameNotFoundException` thrown by `SecurityUtils.getCurrentUser()` — semantically an authentication failure, should be 401.
- `InvalidTokenException` thrown by `JwtService.extractClaims()` when called outside the JWT filter — should be 401.

Additionally, the three places that emit 401 responses produce inconsistent shapes:
- `GlobalExceptionHandler.handleBadCredentials` returns a JSON `ErrorResponse`.
- `JwtAuthenticationFilter` calls `response.sendError(401)` — no body.
- `SecurityConfig`'s `authenticationEntryPoint` calls `res.sendError(401, "Unauthorized")` — plain text, no JSON body.

**Information leaks to callers**

- `EmailAlreadyInUseException` (Finding 10): the 409 body contains the submitted email and confirms account existence — a textbook enumeration oracle.
- `MultipartException` handler (Finding 17): raw servlet/container message appended to the response body; can include filesystem temp paths or internal class names.
- `InvalidDocumentContentException("Resolved path escapes storage root")` (Finding 18): the 400 body confirms to an attacker that a path-traversal probe was detected.
- `IllegalArgumentException` handler (Finding 4): forwards `ex.getMessage()` verbatim; any library-thrown `IllegalArgumentException` (JPA, Jackson, Pageable) carries its message to the client.

**Information leaks to logs**

- `UsernameNotFoundException` message `"User not found: alice@example.com"` reaches the generic ERROR log (Finding 2/20).
- `UserDetailsServiceImpl` logs `"User not found for username: {email}"` at WARN (Finding 20).
- `JwtAuthenticationFilter` logs the full `e.getMessage()` at WARN, which for `UsernameNotFoundException` contains the email (Finding 20).
- `AuthService.register()` logs the plaintext email on duplicate registration; `AuthService.login()` logs the plaintext email on failure — PII in logs reachable without authentication (Findings 6/7).

**JWT payload disclosure**

- `JwtService` sets the subject to the user's email; the JWT payload is base64-decodable by any bearer of the token (Finding 9).

---

## 2. Prioritised Fix List

| # | Finding | Location | Type | Fix | Severity | Effort |
|---|---------|----------|------|-----|----------|--------|
| A | Finding 10 | `EmailAlreadyInUseException`, `GlobalExceptionHandler` | Info leak (email enumeration) | Remove email from exception message; fix `ConflictException` handler to not forward raw message | High | XS |
| B | Finding 2 | `GlobalExceptionHandler`, `SecurityUtils` | Wrong code (500→401) + log leak | Add `@ExceptionHandler(UsernameNotFoundException.class)` returning 401; remove email from exception messages | High | XS |
| C | Finding 17 | `GlobalExceptionHandler.handleMultipart` | Info leak (filesystem path) | Replace raw message append with fixed string | Medium | XS |
| D | Finding 18 / 4 | `InvalidDocumentContentException`, `GlobalExceptionHandler` | Info leak (attack detection) + raw message | Add dedicated handler for `InvalidDocumentContentException`; change path-traversal message; make `IllegalArgumentException` handler generic | Medium | S |
| E | Finding 1 | `GlobalExceptionHandler` | Wrong code (500→401) | Add `@ExceptionHandler(InvalidTokenException.class)` returning 401 | Medium | XS |
| F | Finding 401 consistency | `SecurityConfig`, `JwtAuthenticationFilter` | Wrong code + inconsistent shape | Implement `JsonAuthenticationEntryPoint` writing `ErrorResponse`; wire into filter and SecurityConfig | Medium | S |
| G | Finding 20 | `SecurityUtils`, `UserDetailsServiceImpl`, `JwtAuthenticationFilter` | Log leak (email PII) | Remove email from `UsernameNotFoundException` messages; sanitise filter log | Low | XS |
| H | Finding 6/7 | `AuthService` | Log leak (email PII) | Replace plaintext emails in log statements with hashed values | Low | XS |
| I | Finding 19 | `CommentController` | Wrong code (500 instead of 401/403) | Add `@PreAuthorize("isAuthenticated()")` to `addComment` | Medium | XS |
| J | Finding 9 | `JwtService`, `AuthResponse` | Design (email in JWT sub) | Use UUID as JWT subject; add `expiresIn` to `AuthResponse` | Low | M |

---

## 3. Implementation Steps

### Fix A — Remove email enumeration oracle from `EmailAlreadyInUseException` and `ConflictException` handler

**Problem:** `EmailAlreadyInUseException` embeds the email in its message. The `ConflictException` handler at `GlobalExceptionHandler` line 151 forwards `ex.getMessage()` to the response body, producing `"Email already in use: alice@example.com"` in the 409.

**Step A1 — Change `EmailAlreadyInUseException`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/exception/EmailAlreadyInUseException.java`

```java
public class EmailAlreadyInUseException extends ConflictException {

    public EmailAlreadyInUseException() {
        super("An account with this email already exists", "EMAIL_TAKEN");
    }
}
```

Remove the `email` parameter. The email is no longer part of the exception message so it cannot leak via any handler or log.

**Step A2 — Update the call site in `AuthService`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/AuthService.java`, line 62.

Change:
```java
throw new EmailAlreadyInUseException(request.email());
```
To:
```java
throw new EmailAlreadyInUseException();
```

The WARN log on line 61 already records the email for abuse-investigation purposes (that is its intent — see Finding 6). The exception message no longer needs to carry it.

**No change to `GlobalExceptionHandler.handleConflict`** — it continues to forward `ex.getMessage()`, which is now a safe fixed string.

---

### Fix B — Handle `UsernameNotFoundException` explicitly

**Problem:** `UsernameNotFoundException` has no dedicated handler; it falls to the 500 generic handler. The exception message `"User not found: alice@example.com"` then appears in the ERROR log.

**Step B1 — Add handler to `GlobalExceptionHandler`**

Add after the `handleBadCredentials` handler (line 195):

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("Authentication failed on {}", request.getRequestURI());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

Note: `ex.getMessage()` is intentionally NOT logged here — the email leakage is addressed in Fix G.

**Step B2 — Move `UsernameNotFoundException` import**

Add to imports:
```java
import org.springframework.security.core.userdetails.UsernameNotFoundException;
```

---

### Fix C — Fix `MultipartException` handler to use a fixed message

**Problem:** `GlobalExceptionHandler.handleMultipart` (lines 168–181) appends raw `ex.getMessage()` to the response, potentially exposing filesystem temp paths.

File: `GlobalExceptionHandler.java`, replace the handler body:

```java
@ExceptionHandler(MultipartException.class)
public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex,
                                                     HttpServletRequest request) {
    log.warn("Invalid multipart request on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "Invalid multipart request", request);
}
```

Remove the `MESSAGE_TRUNCATE_LIMIT` constant if it is no longer used by any other handler after this change.

---

### Fix D — Separate `InvalidDocumentContentException` from the generic `IllegalArgumentException` handler

**Problem:** `InvalidDocumentContentException` is a subclass of `IllegalArgumentException`. The path-traversal detection message `"Resolved path escapes storage root"` leaks via the `IllegalArgumentException` handler. Simultaneously, uncontrolled library-thrown `IllegalArgumentException` instances have their messages forwarded to the client.

**Step D1 — Add dedicated handler for `InvalidDocumentContentException`**

`InvalidDocumentContentException` is the application's own type with curated messages. Add a handler before the `IllegalArgumentException` handler (Spring resolves the most-specific match first, but explicit ordering avoids ambiguity):

```java
@ExceptionHandler(InvalidDocumentContentException.class)
public ResponseEntity<ErrorResponse> handleInvalidDocumentContent(InvalidDocumentContentException ex,
                                                                   HttpServletRequest request) {
    log.warn("Invalid document content on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
}
```

This allows the three curated `InvalidDocumentContentException` messages (`"Uploaded file is empty"`, `"Unsupported content type: {contentType}"`, `"Resolved path escapes storage root"`) to be controlled explicitly.

**Step D2 — Change the path-traversal message in `LocalFileStorageService`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/storage/LocalFileStorageService.java`, line 61.

Change:
```java
throw new InvalidDocumentContentException("Resolved path escapes storage root");
```
To:
```java
throw new InvalidDocumentContentException("Invalid file");
```

This is an internal assertion failure (filename sanitisation should have already blocked this). The client gets `"Invalid file"`. The log in the `InvalidDocumentContentException` handler captures the original message for server-side debugging.

**Step D3 — Make the `IllegalArgumentException` handler return a fixed message**

File: `GlobalExceptionHandler.java`, lines 183–188.

Change:
```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                           HttpServletRequest request) {
    log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
}
```

The one legitimate use of the raw message (`"Sort field not allowed: {field}"`) comes from `DocumentController.validateSort()`. That code throws a plain `IllegalArgumentException`. After this change, the client receives `"Invalid request"`. Evaluate whether `DocumentController.validateSort()` should throw a more specific application exception with its own handler and curated message — but that is an optional refinement, not a security requirement.

---

### Fix E — Handle `InvalidTokenException` explicitly

**Problem:** If `InvalidTokenException` escapes the JWT filter (e.g., called from future service code), it falls to the 500 handler.

Add to `GlobalExceptionHandler`:

```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                        HttpServletRequest request) {
    log.warn("Invalid token on {}", request.getRequestURI());
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}
```

Add import:
```java
import com.alexandria.exception.InvalidTokenException;
```

---

### Fix F — 401 consistency: unified `JsonAuthenticationEntryPoint`

(See section 4 for full detail on the problem; this step provides the concrete implementation.)

**Step F1 — Create `JsonAuthenticationEntryPoint`**

New file: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/security/JsonAuthenticationEntryPoint.java`

```java
package com.alexandria.security;

import com.alexandria.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Authentication required",
                Instant.now(),
                request.getRequestURI()
        );
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
```

**Step F2 — Wire into `SecurityConfig`**

Declare the bean in `SecurityConfig` (inject `ObjectMapper` via constructor parameter):

```java
@Bean
public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return new JsonAuthenticationEntryPoint(objectMapper);
}
```

Replace the lambda in `securityFilterChain`:

```java
.exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint))
```

`jsonAuthenticationEntryPoint` is injected as a method parameter of `securityFilterChain`.

**Step F3 — Fix `JwtAuthenticationFilter` to delegate to the entry point instead of calling `sendError` directly**

The filter currently calls `response.sendError(401)` with no body. Instead, it should set the `WWW-Authenticate` challenge or delegate to the entry point. The cleanest approach compatible with the filter's position in the chain is to write the JSON body directly, mirroring what the entry point does, or to store the exception in the request and let the entry point handle it.

Replace lines 47–49 in `JwtAuthenticationFilter`:

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}", request.getRequestURI());
    SecurityContextHolder.clearContext();
    jsonAuthenticationEntryPoint.commence(request, response,
            new org.springframework.security.authentication.BadCredentialsException("Invalid token", e));
    return;
}
```

Inject `JsonAuthenticationEntryPoint` into `JwtAuthenticationFilter` via constructor (add field and update `SecurityConfig` bean factory method):

```java
// JwtAuthenticationFilter constructor fields:
private final JwtService jwtService;
private final UserDetailsService userDetailsService;
private final JsonAuthenticationEntryPoint authenticationEntryPoint;
```

Update `SecurityConfig.jwtAuthenticationFilter`:

```java
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                        UserDetailsService userDetailsService,
                                                        JsonAuthenticationEntryPoint authenticationEntryPoint) {
    return new JwtAuthenticationFilter(jwtService, userDetailsService, authenticationEntryPoint);
}
```

After this change, all three 401 paths (entry point, filter catch block, `handleBadCredentials` handler, `handleUsernameNotFound` handler, `handleInvalidToken` handler) produce identical JSON bodies with the same `ErrorResponse` shape.

---

### Fix G — Remove email from `UsernameNotFoundException` messages and sanitise filter log

**Step G1 — `SecurityUtils.getCurrentUser()`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/security/SecurityUtils.java`, line 21.

Change:
```java
.orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
```
To:
```java
.orElseThrow(() -> new UsernameNotFoundException("Authentication failed"));
```

The `handleUsernameNotFound` handler (Fix B) logs only the path, not the message, so no email enters any log from this path.

**Step G2 — `UserDetailsServiceImpl.loadUserByUsername()`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/security/UserDetailsServiceImpl.java`, line 39.

Change:
```java
log.warn("User not found for username: {}", username);
return new UsernameNotFoundException("User not found: " + username);
```
To:
```java
log.warn("User not found during authentication");
return new UsernameNotFoundException("Authentication failed");
```

**Step G3 — `JwtAuthenticationFilter` catch block log**

The existing log statement (line 47) logs `e.getMessage()`, which for `UsernameNotFoundException` contains the email.

Change:
```java
log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
```
To:
```java
log.warn("JWT authentication failed on {}", request.getRequestURI());
```

---

### Fix H — Replace plaintext email in `AuthService` log statements

**Problem:** `AuthService` logs plaintext emails for audit purposes (Findings 6/7). For GDPR/public-facing deployments the log store becomes a PII store. The recommended mitigation is to log a truncated SHA-256 hex of the lowercase email — traceable but not directly identifying.

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/AuthService.java`.

Add a private helper:

```java
private static String emailHash(String email) {
    try {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(email.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 12);
    } catch (java.security.NoSuchAlgorithmException e) {
        return "[unknown]";
    }
}
```

Replace the four log statements:

- Line 61: `log.warn("Registration attempt with already-used email: {}", emailHash(request.email()));`
- Line 65: `log.info("User registered successfully: {}", emailHash(request.email()));`
- Line 77: `log.warn("Failed login attempt for email: {}", emailHash(request.email()));`
- Line 82: `log.info("User logged in successfully: {}", emailHash(request.email()));`

Note: if the team decides plaintext email logging is acceptable and consistent with their log-access controls and retention policy, Findings 6/7 are acceptable as-is (see section 7). This fix is optional but straightforward.

---

### Fix I — Add explicit authentication annotation to `addComment`

**Problem:** `CommentController.addComment` has no `@PreAuthorize`, so its authentication contract is implicit and fragile — dependent on the `anyRequest().authenticated()` fallback.

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/CommentController.java`, line 44.

Add before `@PostMapping`:

```java
@PreAuthorize("isAuthenticated()")
```

After Fix B, an unauthenticated request to this endpoint would already return 401 from Spring Security's `authenticationEntryPoint` before the controller is reached. The annotation makes the intent explicit in the code and ensures the endpoint remains protected if security configuration changes.

---

### Fix J — Use UUID as JWT subject (optional, design-level)

**Problem:** `JwtService.generateToken()` sets `subject(user.getEmail())`. The email is then base64-decodable from any JWT.

This is a breaking API change if any consumer reads the JWT subject claim. Evaluate carefully.

**Step J1 — `JwtService.generateToken()`**

Change `subject(user.getEmail())` to `subject(user.getId().toString())`.

**Step J2 — `JwtService.extractEmail()` / `JwtAuthenticationFilter`**

If email extraction is needed from the token elsewhere, change the claim used for that purpose (e.g., add a custom `"email"` claim explicitly, or look up by UUID from the subject). The filter currently uses `claims.getSubject()` to load the user by email. After the change, the filter would need to call `userDetailsService.loadUserByUsername(uuid)` or adapt the lookup to use the UUID.

**Step J3 — Add `expiresIn` to `AuthResponse`**

```java
public record AuthResponse(String token, long expiresIn) {}
```

Populate from `JwtService` by reading the configured expiry, not by decoding the token. This is a non-breaking addition for most clients.

---

## 4. The 401 Consistency Problem

**Current state — three different 401 shapes:**

| Source | When it fires | HTTP body |
|--------|---------------|-----------|
| `SecurityConfig` `authenticationEntryPoint` lambda | Request reaches a protected endpoint with no token | `sendError(401, "Unauthorized")` — Tomcat error page, no JSON |
| `JwtAuthenticationFilter` catch block | Token present but invalid/expired | `sendError(401)` — bare Tomcat error page, no body at all |
| `GlobalExceptionHandler.handleBadCredentials` | POST `/api/auth/login` with wrong password | JSON `ErrorResponse` with `status=401`, `message="Invalid credentials"` |

After Fixes B and E are applied, two new JSON-producing handlers are added, but the filter and entry point remain inconsistent.

**Unified fix (Fix F above):**

Create `JsonAuthenticationEntryPoint` that writes the standard `ErrorResponse` record as JSON. Wire it into:
1. `SecurityConfig.exceptionHandling` — replaces the lambda.
2. `JwtAuthenticationFilter.catch` — call `entryPoint.commence(...)` instead of `sendError`.

After the fix, all 401 responses have this shape:

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication required",
  "timestamp": "2026-06-14T10:00:00Z",
  "path": "/api/documents/..."
}
```

The `handleBadCredentials` handler can retain its distinct `"Invalid credentials"` message because it is a legitimate credential failure, not a missing/invalid token.

---

## 5. Log Hygiene Changes

| Location | Line | Current statement | Change |
|----------|------|-------------------|--------|
| `JwtAuthenticationFilter.java` | 47 | `log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage())` | Remove `e.getMessage()` — contains email when exception is `UsernameNotFoundException` |
| `JwtAuthenticationFilter.java` | 44 | `log.debug("Authenticated user: {}", email)` | Acceptable at DEBUG; document that DEBUG must not be enabled on the security package in production |
| `UserDetailsServiceImpl.java` | 38 | `log.warn("User not found for username: {}", username)` | Remove `username` argument; change to `log.warn("User not found during authentication")` |
| `SecurityUtils.java` | 21 | Exception message `"User not found: " + email` | Change message to `"Authentication failed"` (email removed from message — no log change needed) |
| `AuthService.java` | 61 | `log.warn("Registration attempt with already-used email: {}", request.email())` | Replace `request.email()` with `emailHash(request.email())` (Fix H, optional) |
| `AuthService.java` | 65 | `log.info("User registered successfully: {}", request.email())` | Replace with `emailHash(...)` (Fix H, optional) |
| `AuthService.java` | 77 | `log.warn("Failed login attempt for email: {}", request.email())` | Replace with `emailHash(...)` (Fix H, optional) |
| `AuthService.java` | 82 | `log.info("User logged in successfully: {}", request.email())` | Replace with `emailHash(...)` (Fix H, optional) |
| `GlobalExceptionHandler.java` | 200 | `log.error("Unexpected error on {}: {}", request.getRequestURI(), ex.getMessage(), ex)` | Acceptable as-is (server-side only); if stricter hygiene is required, log only `ex.getClass().getName()` without the message |

---

## 6. Recommended Implementation Order

The fixes are ordered to produce a shippable, green-test result after each step.

**Wave 1 — High severity, zero-risk, XS effort (do first, no test breakage expected):**

1. **Fix A** — `EmailAlreadyInUseException`: remove email parameter, update call site. Tests for registration conflict will need their expected message string updated.
2. **Fix B** — Add `UsernameNotFoundException` handler. Tests that expect 500 from this path (if any) change to expect 401.
3. **Fix E** — Add `InvalidTokenException` handler. Likely no existing test for this case.
4. **Fix I** — Add `@PreAuthorize("isAuthenticated()")` to `addComment`.

**Wave 2 — Medium severity, small code changes:**

5. **Fix C** — `MultipartException` handler: remove raw message append.
6. **Fix D** — Add `InvalidDocumentContentException` handler; change path-traversal message; make `IllegalArgumentException` handler generic.
7. **Fix G** — Remove email from `UsernameNotFoundException` messages in `SecurityUtils` and `UserDetailsServiceImpl`; sanitise filter WARN log.

**Wave 3 — 401 consistency (requires new class, touches filter and SecurityConfig):**

8. **Fix F** — Create `JsonAuthenticationEntryPoint`; inject into filter; update `SecurityConfig`.

**Wave 4 — Optional / design-level:**

9. **Fix H** — Replace plaintext email in `AuthService` logs with hashed values (privacy consideration; acceptable to defer).
10. **Fix J** — Use UUID as JWT subject; add `expiresIn` to `AuthResponse` (breaking change, needs consumer assessment).

---

## 7. Acceptable As-Is

The following findings from the audit require no code changes and are recorded here as confirmed-reviewed:

| Finding | Verdict |
|---------|---------|
| Finding 5 — `MethodArgumentTypeMismatchException` echoes caller's own value/name | Not an information leak; standard REST practice |
| Finding 8 — `JwtAuthenticationFilter` logs email at DEBUG | Acceptable at DEBUG level; document that DEBUG must not be enabled on the security package in production |
| Finding 11 — `ForbiddenException` handler mapping | Correct polymorphic matching; no issue |
| Finding 12 — `ReadingListItemAlreadyExistsException` echoes UUIDs | Caller's own input echoed back; no server-secret leak; cosmetic only |
| Finding 13 — `DocumentNotFoundException` echoes UUID; 404-masking of PRIVATE documents | Correct security-through-obscurity pattern; UUID randomness prevents enumeration |
| Finding 14 — 406 handler returns no `Accept` hint | No information leak; RFC-compliant; improving it is a DX nicety |
| Finding 15 — Stack traces absent from responses | Confirmed safe |
| Finding 16 — CORS violations return empty 403 | No information leak |
| Finding 3 — Generic handler logs `ex.getMessage()` | Server-side log only; acceptable for most deployments; ensure log aggregation access controls are in place |
| Finding 7 — Login failure logging writes attempted email | Necessary security audit logging; no client-visible response change |
