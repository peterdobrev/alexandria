# Error Handling & Information Disclosure — Security Audit

**Audited:** 2026-06-14  
**Scope:** GlobalExceptionHandler, ErrorResponse, JsonAuthenticationEntryPoint, JwtAuthenticationFilter,
JwtService, AuthService, DocumentService, CommentService, ReadingListService, UserService,
CategoryService, LocalFileStorageService, DocumentController  

---

## Summary

The error-handling layer is substantially well-built. Checked exceptions are absent, a single
`@RestControllerAdvice` covers nearly all Spring MVC exceptions, the `ErrorResponse` record is
lean, and `JsonAuthenticationEntryPoint` reuses the same DTO structure. There are no stack traces
returned to callers. The findings below are real issues, but none are critical; the most impactful
ones are MEDIUM severity.

---

## Findings

### F-01 — `InvalidDocumentContentException` message exposed verbatim to the client

**Severity:** MEDIUM  
**File:** `exception/InvalidDocumentContentException.java` (inherits `IllegalArgumentException`)  
**Handler:** `GlobalExceptionHandler.java` lines 184–189

`InvalidDocumentContentException` extends `IllegalArgumentException`. The handler for
`IllegalArgumentException` intentionally discards the message and returns a generic string:

```java
// GlobalExceptionHandler.java line 188
return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
```

However, `LocalFileStorageService` constructs instances with internal detail:

```java
// LocalFileStorageService.java line 43-44
throw new InvalidDocumentContentException(
        "Unsupported content type: " + contentType);
```

```java
// LocalFileStorageService.java line 61
throw new InvalidDocumentContentException("Resolved path escapes storage root");
```

Because the handler catches the exception by its supertype (`IllegalArgumentException`) and then
discards the message, the client only sees `"Invalid request"` — which is already safe. However,
the design is fragile: if a future handler for `InvalidDocumentContentException` is added with
`ex.getMessage()` forwarded (a natural next step to give better validation messages), the "Resolved
path escapes storage root" message — which reveals a path-traversal detection mechanism — and the
raw content-type string would leak.

**Recommendation:** Give `InvalidDocumentContentException` its own `@ExceptionHandler` that
returns a safe, stable message for client-facing cases and logs the detail internally. Separate the
"content-type not allowed" case (safe to surface) from the "path escapes root" case (must never
surface) by using distinct exception subclasses.

---

### F-02 — `MultipartException` handler echoes the raw library message to the client

**Severity:** MEDIUM  
**File:** `GlobalExceptionHandler.java` lines 169–182

```java
@ExceptionHandler(MultipartException.class)
public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex,
                                                     HttpServletRequest request) {
    String detail = ex.getMessage();
    String message = "Invalid multipart request";
    if (detail != null && !detail.isBlank()) {
        String truncated = detail.length() > MESSAGE_TRUNCATE_LIMIT
                ? detail.substring(0, MESSAGE_TRUNCATE_LIMIT)
                : detail;
        message = message + ": " + truncated;   // <-- third-party message appended
    }
    ...
    return build(HttpStatus.BAD_REQUEST, message, request);
}
```

The message from Spring's `MultipartException` (which wraps Apache Commons `FileUploadException`,
Tomcat's multipart parser, etc.) is truncated to 200 characters but then directly appended to the
client response. That message can contain:

- Internal class names (`org.apache.tomcat.util.http.fileupload.impl.SizeException`)  
- Server-side configuration values ("the request was rejected because its size (67108865) exceeds the configured maximum (67108864)")  
- File system paths in some container configurations

**Recommendation:** Remove the detail appending. Return only the fixed string
`"Invalid multipart request"` and log `ex.getMessage()` at `WARN` level (already done).

---

### F-03 — `ConstraintViolationException` handler exposes internal property path names

**Severity:** MEDIUM  
**File:** `GlobalExceptionHandler.java` lines 50–59

```java
String message = ex.getConstraintViolations().stream()
        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
        .collect(Collectors.joining(", "));
```

The property path in a `ConstraintViolationException` (fired by bean-validation on `@Validated`
service-layer or controller parameters) includes the full method signature path, e.g.:

```
userService.update.request.password: size must be between 8 and 128
documentService.create.file: must not be null
```

This reveals:

- Internal service class names (`userService`, `documentService`)  
- Internal method names (`update`, `create`)  
- Internal parameter names (`request`, `file`)

`MethodArgumentNotValidException` (lines 39–48) is correctly handled — it only exposes
`FieldError::getDefaultMessage`. `ConstraintViolationException` should apply the same pattern:
strip the property path prefix and expose only the leaf field name and the constraint message.

**Recommendation:**

```java
String message = ex.getConstraintViolations().stream()
        .map(v -> {
            String path = v.getPropertyPath().toString();
            // keep only the last segment (the field/parameter name)
            int dot = path.lastIndexOf('.');
            String field = dot >= 0 ? path.substring(dot + 1) : path;
            return field + ": " + v.getMessage();
        })
        .collect(Collectors.joining(", "));
```

---

### F-04 — `UncheckedIOException` from `LocalFileStorageService.store()` falls through to the generic 500 handler

**Severity:** MEDIUM  
**File:** `storage/LocalFileStorageService.java` lines 63–71

```java
} catch (IOException e) {
    log.error("Failed to write uploaded file to {}", target, e);
    throw new UncheckedIOException("Failed to store uploaded file", e);
}
```

`UncheckedIOException` is a `RuntimeException` not handled by any specific `@ExceptionHandler`.
It propagates to the catch-all `handleGeneric` method which returns HTTP 500 with the message
`"An unexpected error occurred"`. The message itself is safe, but the cause chain contains
`IOException` with OS-level detail (disk full, permissions, actual file path including the
server-side storage root) which is logged. The log statement on line 69 includes the full
`target` path (an absolute `Path` built from `storageProperties.root()`).

This is a server log concern rather than a client response concern, but the 500 status is also
semantically wrong: a disk-full or misconfigured storage root is an infrastructure failure, not a
transient "unexpected error". The client cannot distinguish this from a bug.

**Recommendation:** Introduce a `StorageException` (extends `RuntimeException`) and add an
`@ExceptionHandler` that returns 503 (Service Unavailable) with a fixed message such as
`"File storage is temporarily unavailable"`. Log the full cause internally. Replace
`UncheckedIOException` in `store()` and `load()` with `StorageException`.

---

### F-05 — `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException` with the user's email address

**Severity:** MEDIUM  
**File:** `security/SecurityUtils.java` lines 21–22

```java
return userRepository.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
```

When an authenticated session exists but the corresponding database row has been deleted between
token issuance and request (or after a data migration), `UsernameNotFoundException` is thrown with
the literal email in the message. The `GlobalExceptionHandler` catches this at lines 205–210 and
maps it to HTTP 401 with the generic message `"Authentication required"` — so the email does not
reach the client. However, the same message is written to `log.warn` on line 208:

```java
log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
```

The log entry contains `"User not found: alice@example.com"` — PII in a warning log. This is a
log-level disclosure issue: anyone with access to the application log sees the email of deleted or
suspended users on every authenticated request they attempt.

**Recommendation:** Either omit the email from the exception message and log the hashed form (as
`AuthService` does), or hash it in the log statement itself. The `GlobalExceptionHandler` log line
should be `log.warn("User account not found on {}: [redacted]", request.getRequestURI())` or use
the SHA-256 prefix pattern already established in `AuthService`.

---

### F-06 — `AuthService.register()` throws `IllegalStateException` with the role name when the default role is missing

**Severity:** LOW  
**File:** `service/AuthService.java` lines 56–59

```java
Role role = roleRepository.findByName(RoleNames.USER)
        .orElseThrow(() -> new IllegalStateException(
                "Default role " + RoleNames.USER + " not configured"));
```

`IllegalStateException` is caught by the catch-all `handleGeneric` handler and returns HTTP 500
with the generic message `"An unexpected error occurred"`. The actual message — which names the
role constant — does not leave the server. However, the `handleGeneric` handler logs at `ERROR`
with `ex.getMessage()` and the stack trace (line 215):

```java
log.error("Unexpected error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
```

The log will contain `"Default role ROLE_USER not configured"`, which is acceptable internal
information. The finding is that `IllegalStateException` is used for a startup/misconfiguration
condition that surfaces as a 500 during a live registration request. Using a dedicated
`ConfigurationException` (or `IllegalStateException` caught and re-thrown before any user data is
processed) would make the operational signal clearer without changing the external behaviour.

**Recommendation:** This is a misconfiguration guard; throw `IllegalStateException` from a
`@PostConstruct` or `ApplicationRunner` so it fails at startup rather than silently during the
first registration attempt. No client-visible change required.

---

### F-07 — `UserService.update()` logs the user's email address in plain text on password change

**Severity:** LOW  
**File:** `service/UserService.java` line 40

```java
log.info("Password changed for user: {}", user.getEmail());
```

The email address is PII. Logging it at `INFO` level means it appears in any log sink (Splunk,
CloudWatch, stdout in Kubernetes) visible to operations and security teams, and potentially in
audit exports. While logging password-change events is a security best practice, the identifier
should be a stable opaque ID or a hashed email, consistent with the approach used throughout
`AuthService`.

**Recommendation:** Replace with `log.info("Password changed for user: {}", user.getId())` to log
the UUID, which is already a non-sensitive identifier used in URLs and audit trails.

---

### F-08 — `ErrorResponse` does not include the `errorCode` from domain exceptions, forcing clients to parse free-text messages

**Severity:** LOW  
**File:** `dto/ErrorResponse.java`

```java
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        String path
) {}
```

The domain exception classes (`NotFoundException`, `ConflictException`, `ForbiddenException`)
all carry a typed `errorCode` field (e.g. `"DOCUMENT_NOT_FOUND"`, `"EMAIL_TAKEN"`,
`"READING_LIST_ITEM_ALREADY_EXISTS"`). This code is never serialised into the `ErrorResponse`.
Clients that need to branch on the kind of error (e.g. show "email already taken" vs a generic
conflict message) must parse the `message` string, which is fragile. The `errorCode` was clearly
designed for client consumption but is silently discarded by the handlers.

This is not a security issue, but it is an API contract deficiency that can lead to clients
implementing workaround message-string parsing, which in turn can be exploited if message wording
ever changes.

**Recommendation:** Add an optional `String errorCode` field to `ErrorResponse`. Populate it in
the `handleNotFound`, `handleForbidden`, and `handleConflict` handlers by calling
`ex.getErrorCode()`. Leave it `null` for infrastructure/unexpected exceptions.

---

### F-09 — `NoHandlerFoundException` is not explicitly handled; relies on `NoResourceFoundException` coverage

**Severity:** LOW  
**File:** `GlobalExceptionHandler.java`

Spring Boot 3.x replaces `NoHandlerFoundException` with `NoResourceFoundException` (from
`spring-webmvc` 6.1). The handler at lines 127–132 covers `NoResourceFoundException`. However,
if `spring.mvc.throw-exception-if-no-handler-found=true` is set and static resource handling is
disabled, Spring can still throw the old `NoHandlerFoundException`. There is no explicit handler
for it. It would fall through to the catch-all `handleGeneric` and return 500 instead of 404.

Currently neither `application.properties` nor any configuration class sets this flag, so this is
a latent risk rather than an active one.

**Recommendation:** Add an `@ExceptionHandler(NoHandlerFoundException.class)` returning 404, or
confirm the flag is permanently unset via a documented architecture decision.

---

### F-10 — `JwtAuthenticationFilter` logs the JWT library exception message at WARN level

**Severity:** LOW  
**File:** `security/JwtAuthenticationFilter.java` line 50

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
```

`InvalidTokenException` wraps `JwtException` from the JJWT library. The `ex.getMessage()` passed
to the `InvalidTokenException` constructor is always one of the two fixed strings defined in
`JwtService` ("JWT token has expired", "JWT token is invalid or malformed"), so this is currently
safe. The risk is that `IllegalArgumentException` is also caught here; JJWT can in theory throw
`IllegalArgumentException` with messages that include token fragments or claim values in future
library versions. If the JJWT library is upgraded, the logged message should still be reviewed.

**Recommendation:** Log `e.getClass().getSimpleName()` together with the fixed message rather than
`e.getMessage()` so a future library upgrade cannot accidentally add token data to the log.

---

### F-11 — `MediaType.parseMediaType()` in `DocumentController.streamFile()` can throw unchecked `InvalidMediaTypeException`

**Severity:** LOW  
**File:** `controller/DocumentController.java` line 121

```java
.contentType(MediaType.parseMediaType(sfr.contentType()))
```

`sfr.contentType()` is the raw string stored in the database at upload time (populated from
`file.getContentType()` which comes from the HTTP `Content-Type` header, sanitised only by the
allow-list check in `LocalFileStorageService`). If a stored value is somehow malformed,
`MediaType.parseMediaType()` throws `InvalidMediaTypeException` (extends `IllegalArgumentException`).
This is caught by `handleIllegalArgument` and returns 400 with `"Invalid request"`.

The problem is semantic: this is a server-side data integrity problem (bad value in the database)
being reported as a client error (400). The client's request was valid; it asked for a document by
a known ID. If the stored content-type is corrupt, the correct response is 500.

**Recommendation:** Validate and normalise the content-type at write time (already done via
allow-list), and add a null/blank guard before `parseMediaType()` to produce a clear 500 rather
than a misleading 400.

---

## Non-Findings (Items Audited and Found Clean)

| Check | Result |
|---|---|
| Stack traces in responses | Not present. `handleGeneric` returns only `"An unexpected error occurred"`. |
| Spring Boot default `/error` endpoint | Not reachable for mapped exceptions. All common exceptions are handled. |
| User enumeration via login (user not found vs wrong password) | Correctly mitigated in `AuthService` — constant-time BCrypt run regardless, same `"Invalid credentials"` message for both cases. |
| JWT library exception messages leaking to clients | Wrapped cleanly in `InvalidTokenException` with fixed messages before reaching handlers. |
| Database exception messages leaking to clients | `DataIntegrityViolationException` in `AuthService` is caught and converted to `EmailAlreadyInUseException`; all other DB exceptions propagate to the generic 500 handler which discards the message. |
| File paths in responses | Not present. `LocalFileStorageService` stores only `relativePath` (UUID shard path, no absolute root). `DocumentDetail` does not expose the path. |
| `AccessDeniedException` message | Correctly suppressed; always returns `"Access denied"`. |
| `ErrorResponse` consistent structure | All handlers use the same `build()` helper. `JsonAuthenticationEntryPoint` constructs `ErrorResponse` directly with the same fields and identical message convention. |
| Passwords/tokens in error logs | Login password is never logged. Token content is not logged. Passwords are logged only as encoded hashes (never). |
| Swallowed exceptions | The only silent catch is `fileStorage.delete()` in `DocumentService.delete()` after-commit hook (line 127), which logs at `WARN` — this is intentional and documented. |
| `BadCredentialsException` message | Correctly suppressed; handler always returns `"Invalid credentials"`. |
| `IllegalArgumentException` message | Correctly suppressed; handler always returns `"Invalid request"`. |
| `sort` field injection via `IllegalArgumentException` | `validateSort` in `DocumentController` throws `IllegalArgumentException`; caught and returns `"Invalid request"` — safe, correct 400. |
