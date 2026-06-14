# Adversarial Verification — Error Handling & Information Disclosure Audit (05)

**Verified:** 2026-06-14  
**Against report:** `docs/api-verification/05-error-handling-disclosure.md`  
**Verifier role:** Adversarial reviewer — confirm, refute, or qualify every finding.

---

## Methodology

For each finding the exact source lines were read directly from the codebase. Verdicts are:

- **CONFIRMED** — the code matches the report's description and the risk is real.
- **PARTIALLY-CORRECT** — the technical observation is accurate but the severity or framing is wrong.
- **REFUTED** — the code contradicts the report's claim.

---

## F-01 — `InvalidDocumentContentException` message exposed verbatim to the client

**Verdict: PARTIALLY-CORRECT**

The report correctly identifies that `InvalidDocumentContentException` extends `IllegalArgumentException`
(confirmed: `exception/InvalidDocumentContentException.java` line 3), and that the
`handleIllegalArgument` handler at `GlobalExceptionHandler.java` lines 184–189 discards the message
and returns the fixed string `"Invalid request"`. That part is accurate.

However, the report calls this a MEDIUM-severity finding, framing the issue as an active risk.
The current code is **safe**: no message from this exception reaches the client. The concern raised
is entirely hypothetical — "if a future handler is added". As written, F-01 is a design fragility
observation, not a current vulnerability. Severity should be LOW, not MEDIUM.

The specific messages cited (lines 43–44 and line 60 of `LocalFileStorageService.java`) are
confirmed at the correct locations. The path-traversal detection message `"Resolved path escapes
storage root"` does appear at line 60 (in `store()`) and separately at line 85 (in `load()`), the
latter unmentioned in the report.

---

## F-02 — `MultipartException` handler echoes the raw library message to the client

**Verdict: CONFIRMED**

Code at `GlobalExceptionHandler.java` lines 169–182 matches the quoted snippet exactly. The
truncation-then-append logic is present and the third-party message content (internal class names,
configured size limits) can reach the response body. The `MESSAGE_TRUNCATE_LIMIT` constant of 200
is confirmed at line 37.

Note that `MaxUploadSizeExceededException` (a subclass of `MultipartException`) has its own
handler at lines 162–167 that returns a fixed message, so the most common large-upload case is
safe. The residual risk is for other `MultipartException` subtypes from the container's multipart
parser. The finding stands.

---

## F-03 — `ConstraintViolationException` handler exposes internal property path names

**Verdict: CONFIRMED**

`GlobalExceptionHandler.java` lines 53–55 match the quoted code verbatim:

```java
String message = ex.getConstraintViolations().stream()
        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
        .collect(Collectors.joining(", "));
```

The full method-signature path (e.g. `userService.update.request.password`) is returned in the
400 response body. The contrast with the correctly handled `MethodArgumentNotValidException`
(lines 42–44, which uses only `FieldError::getDefaultMessage`) is accurate. Finding confirmed.

---

## F-04 — `UncheckedIOException` from `LocalFileStorageService.store()` falls through to the generic 500 handler

**Verdict: CONFIRMED — with a correction to the line number**

`LocalFileStorageService.java` lines 68–71 match the description. The exception is thrown at
line 70, not at the "lines 63–71" range in the report (the range is the try-catch block; the throw
is line 70). Minor numbering imprecision, not a substantive error.

The claim that there is no specific `@ExceptionHandler` for `UncheckedIOException` is confirmed:
the `GlobalExceptionHandler` has no handler for `UncheckedIOException` or `IOException`. It falls
through to `handleGeneric` at line 212.

The client message (`"An unexpected error occurred"`) is safe. The report's complaint about 500
vs. 503 semantics is valid but is an operational concern, not a security issue. The severity of
MEDIUM is arguable; LOW would be more accurate since no information leaks to the client.

One additional detail the report omits: the log statement at line 69 logs the `target` variable
(the absolute resolved path including the storage root) as a structured Throwable argument, meaning
it goes into log context rather than the message string. The path does appear in the log, but only
as part of the exception detail, not in the structured log fields. This is a minor distinction but
does not affect the verdict.

---

## F-05 — `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException` with the user's email address

**Verdict: CONFIRMED**

`SecurityUtils.java` lines 20–22 match the quoted snippet exactly:

```java
return userRepository.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
```

The `GlobalExceptionHandler.java` lines 205–210 handler for `UsernameNotFoundException` is
confirmed to suppress the message from the client response (returns `"Authentication required"`).
However the log statement at line 208:

```java
log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
```

will emit `"User not found: alice@example.com"` to the application log. This is PII in a warn log,
as the report states. Finding confirmed.

The contrast with `AuthService` (which uses `emailHash()` throughout its logging) is accurate and
points to an inconsistency in the codebase's own conventions.

---

## F-06 — `AuthService.register()` throws `IllegalStateException` with the role name when the default role is missing

**Verdict: CONFIRMED — but severity should be LOW, not LOW**

`AuthService.java` lines 56–58 match the quoted snippet exactly. The `IllegalStateException`
falls to `handleGeneric` and the message `"Default role ROLE_USER not configured"` is written
to the error log. The client receives only `"An unexpected error occurred"`. No information leaks
externally.

The report correctly identifies this as a misconfiguration guard that should fail at startup.
The finding is accurate. Severity LOW is appropriate.

---

## F-07 — `UserService.update()` logs the user's email address in plain text on password change

**Verdict: CONFIRMED**

`UserService.java` line 39 reads:

```java
log.info("Password changed for user: {}", user.getEmail());
```

The email is logged at INFO level. This is inconsistent with `AuthService`, which hashes all
email addresses before logging them (`emailHash(request.email())`). Finding confirmed; severity
LOW is appropriate.

---

## F-08 — `ErrorResponse` does not include the `errorCode` from domain exceptions

**Verdict: CONFIRMED**

`ErrorResponse.java` (lines 5–11) is a five-field record with no `errorCode` field:

```java
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        String path
) {}
```

`NotFoundException`, `ConflictException`, and `ForbiddenException` all carry a `@Getter`
`errorCode` field (confirmed in the abstract base classes). The handlers for these exceptions
(`handleNotFound`, `handleForbidden`, `handleConflict`) call `build()` with only the string
message, silently discarding `ex.getErrorCode()`. The report is accurate.

This is not a security issue. Classifying it under an error-handling security audit is a slight
scope overreach, but the finding itself is technically correct.

---

## F-09 — `NoHandlerFoundException` is not explicitly handled

**Verdict: CONFIRMED — with an important qualification**

The `GlobalExceptionHandler` has no `@ExceptionHandler(NoHandlerFoundException.class)`. If this
exception were ever thrown it would fall to `handleGeneric` and return 500 instead of 404.

However, the report itself acknowledges that the triggering condition (`spring.mvc.throw-exception-if-no-handler-found=true`) is not set in `application.properties`, and the file was verified — it is absent. Spring Boot 3.x default behaviour routes unmapped requests to
`NoResourceFoundException`, which IS handled at lines 127–132. The finding is a latent/theoretical
risk only. Severity LOW is correct; the recommendation to explicitly document the decision is
reasonable.

---

## F-10 — `JwtAuthenticationFilter` logs the JWT library exception message at WARN level

**Verdict: PARTIALLY-CORRECT**

`JwtAuthenticationFilter.java` line 50 matches the quoted code:

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
```

The report's claim that `InvalidTokenException` wraps JJWT with only two fixed strings is
confirmed by `JwtService.java` lines 76–80: the only messages assigned are `"JWT token has
expired"` and `"JWT token is invalid or malformed"`. So the `InvalidTokenException` branch is
currently safe.

However, the concern about `IllegalArgumentException` is overstated. The filter catches
`IllegalArgumentException` — but looking at the code, the only path that can throw it here is
`jwtService.extractClaims(token)`. That method's catch block at lines 76–80 of `JwtService.java`
catches all `JwtException` (the JJWT supertype, which includes all JJWT `IllegalArgumentException`
subclasses) and wraps them in `InvalidTokenException` before re-throwing. No raw JJWT
`IllegalArgumentException` can escape `extractClaims()`. The residual `IllegalArgumentException`
in the filter catch clause would only fire for a truly unexpected programming error, not a JJWT
library change. The report's risk level is therefore slightly exaggerated, though the defensive
recommendation still has merit. Severity LOW is appropriate.

---

## F-11 — `MediaType.parseMediaType()` in `DocumentController.streamFile()` can throw unchecked `InvalidMediaTypeException`

**Verdict: CONFIRMED**

`DocumentController.java` line 120:

```java
.contentType(MediaType.parseMediaType(sfr.contentType()))
```

The `contentType` value comes from `DocumentService.streamFile()` which reads
`document.getContentType()` — a raw `String` stored in the database at upload time. There is no
null or blank guard before this call. `InvalidMediaTypeException` extends `IllegalArgumentException`
and would be caught by `handleIllegalArgument`, returning HTTP 400 with `"Invalid request"`.

The report's semantic complaint is valid: a corrupt database value causing a 400 misleads the
client into thinking their request was malformed. This is a data-integrity failure that should
produce a 500.

Additional detail the report misses: `sfr.contentType()` can also be `null` (the `contentType`
column on `Document` is presumably nullable, since newly created articles have no file). If
`document.getContentType()` is `null`, `MediaType.parseMediaType(null)` will throw
`IllegalArgumentException` with a null-pointer message, which is also caught and returned as 400.
Articles (no uploaded file) should never reach `streamFile()` because the method checks
`document.getUploadedFilePath() == null` and throws `DocumentNotFoundException` at line 180 of
`DocumentService.java` before the content-type is ever used. However the null-path protection is
conditional on the file-path check, not on the content-type check itself. The report's finding
therefore holds.

---

## Non-Findings Review

The report's non-findings table was also checked against the source.

| Claim | Verdict |
|---|---|
| No stack traces in responses | CONFIRMED. `handleGeneric` returns only a fixed string. No handler calls `ex.printStackTrace()` or forwards stack detail. |
| Spring Boot default `/error` endpoint | No `server.error.include-stacktrace` or `server.error.include-message` properties are set in `application.properties`. Spring Boot 3.x defaults both to `never`, so the `/error` endpoint would not expose stack traces or messages even if reached. CONFIRMED. |
| User enumeration via login | CONFIRMED. `AuthService.login()` always runs BCrypt (dummy hash when user absent) and returns `BadCredentialsException(INVALID_CREDENTIALS)` for both failure cases. |
| JWT library messages leaking to clients | CONFIRMED. `JwtService` wraps all JJWT exceptions with fixed-string messages before they can propagate. |
| DB exception messages leaking | CONFIRMED. `DataIntegrityViolationException` is caught and re-thrown as `EmailAlreadyInUseException` in `AuthService`. All other DB exceptions reach `handleGeneric` which discards the message. |
| File paths in responses | CONFIRMED. `DocumentService` stores and exposes only `relativePath` (UUID shard path). No absolute root appears in any DTO. |
| `AccessDeniedException` message | CONFIRMED. Handler at line 155 always returns `"Access denied"`. |
| Swallowed exceptions | CONFIRMED. The only silent catch is the after-commit file-delete in `DocumentService.delete()` (lines 126–129), which logs at WARN. This is intentional. |
| `BadCredentialsException` message | CONFIRMED. Handler at lines 191–195 always returns `"Invalid credentials"`. |
| `IllegalArgumentException` message | CONFIRMED. Handler at lines 184–188 always returns `"Invalid request"`. |

---

## Summary

| Finding | Verdict | Correct Severity |
|---|---|---|
| F-01 InvalidDocumentContentException fragile design | PARTIALLY-CORRECT (hypothetical, not active) | LOW (not MEDIUM) |
| F-02 MultipartException raw message appended | CONFIRMED | MEDIUM |
| F-03 ConstraintViolationException exposes property paths | CONFIRMED | MEDIUM |
| F-04 UncheckedIOException falls to generic 500 | CONFIRMED (minor line-number imprecision) | LOW (not MEDIUM; no client leakage) |
| F-05 UsernameNotFoundException logs PII email | CONFIRMED | MEDIUM |
| F-06 IllegalStateException for missing role | CONFIRMED | LOW |
| F-07 UserService logs email in plain text | CONFIRMED | LOW |
| F-08 errorCode not serialised in ErrorResponse | CONFIRMED (not a security issue) | LOW |
| F-09 NoHandlerFoundException not handled | CONFIRMED (latent only) | LOW |
| F-10 JwtAuthenticationFilter logs ex.getMessage() | PARTIALLY-CORRECT (IllegalArgumentException risk overstated) | LOW |
| F-11 parseMediaType can throw on corrupt DB value | CONFIRMED | LOW |

**Counts:**
- CONFIRMED: 8
- PARTIALLY-CORRECT: 3 (F-01, F-10 overstate risk; F-04 has a line-number imprecision and overstated severity)
- REFUTED: 0

**Severity corrections:**
- F-01 should be LOW, not MEDIUM — the current code is safe; the finding is a forward-looking design concern.
- F-04 should be LOW, not MEDIUM — no information leaks to the client; the issue is semantic (500 vs 503) and operational.

The Non-Findings section is entirely accurate. No false negatives were found.
