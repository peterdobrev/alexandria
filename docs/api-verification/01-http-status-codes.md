# HTTP Status Code Audit — Alexandria API

Audited on: 2026-06-14  
Scope: all `@RestController` classes + `GlobalExceptionHandler` + every exception class under `com.alexandria.exception`

---

## Summary table

| # | File | Line(s) | Severity | Issue |
|---|------|---------|----------|-------|
| 1 | `RecommendationController.java` | 52–61 | HIGH | POST `/documents/{id}/interactions` returns 204 instead of 201 |
| 2 | `RecommendationController.java` | 52–61 | MEDIUM | POST endpoint for logging interactions semantically should be 201 or at minimum not 204 |
| 3 | `DocumentController.java` | 100–105 | MEDIUM | `PUT /{id}` returns raw object (200 implicit) — no `ResponseEntity`, which is fine, but `update` also has no `@ResponseStatus` annotation making the intent invisible |
| 4 | `DocumentController.java` | 67–72 | MEDIUM | `GET /{id}` returns raw object (200 implicit) — no `ResponseEntity` and no `@ResponseStatus` |
| 5 | `DocumentController.java` | 52–65 | MEDIUM | `GET /` returns raw object (200 implicit) — no `ResponseEntity` and no `@ResponseStatus` |
| 6 | `GlobalExceptionHandler.java` | 205–210 | HIGH | `UsernameNotFoundException` mapped to `401 Unauthorized` — but this exception is thrown during JWT filter user-lookup (account deleted after token issue) and also leaks through `UserService`/`UserNotFoundException` chain misidentification |
| 7 | `GlobalExceptionHandler.java` | 205–210 | HIGH | `UsernameNotFoundException` from `UserDetailsServiceImpl` during JWT filter is swallowed by the filter and re-thrown as `BadCredentialsException`, so the handler at line 205 is dead code for that path; but if `UsernameNotFoundException` ever escapes to the servlet dispatcher (e.g. from a future service call), the mapping to `401` is semantically wrong — a missing user resource should be `404`, not `401` |
| 8 | `InvalidDocumentContentException.java` | 1–8 | HIGH | Extends `IllegalArgumentException`, which is caught by the `handleIllegalArgument` handler and returns `400 Bad_Request` with the **message replaced by** the generic string `"Invalid request"` — the specific content-validation message is silently discarded |
| 9 | `GlobalExceptionHandler.java` | 184–189 | MEDIUM | `handleIllegalArgument` always returns the opaque message `"Invalid request"` regardless of what `IllegalArgumentException.getMessage()` says — `DocumentController.validateSort` (line 141) throws `IllegalArgumentException("Sort field not allowed: " + field)` with a useful message that is discarded |
| 10 | `OwnershipService.java` (indirect) | 28–69 | HIGH | `isDocumentOwner`, `isReadingListOwner`, and `isCommentOwnerOrAdmin` throw `DocumentNotFoundException` / `ReadingListNotFoundException` / `CommentNotFoundException` when the resource is not found during a `@PreAuthorize` expression evaluation — Spring Security catches the resulting `AccessDeniedException` wrapper and returns `403 Forbidden` instead of `404 Not Found` for a non-existent resource |
| 11 | `RecommendationController.java` | 39–49 | MEDIUM | `GET /recommendations` throws `IllegalArgumentException` for pagination bounds violations (lines 41–47); this is caught by `handleIllegalArgument` and returns `400`, which is correct, but the generic `"Invalid request"` message hides which constraint was violated |
| 12 | `AuthController.java` | 35–38 | LOW | `POST /login` returns `200 OK` — this is standard for login endpoints that return tokens (not creating a persistent resource), so 200 is acceptable, but many APIs use `201` here for consistency; this is a design opinion finding, not a clear bug |

---

## Detailed findings

---

### Finding 1 — POST `/documents/{id}/interactions` returns 204 No Content (HIGH)

**File:** `RecommendationController.java` lines 52–61

```java
@ResponseStatus(HttpStatus.NO_CONTENT)
@PostMapping("/documents/{id}/interactions")
public void logInteraction(@PathVariable("id") UUID documentId,
                           @Valid @RequestBody CreateInteractionRequest request) {
    if (request.kind() != InteractionKind.VIEW) {
        throw new IllegalArgumentException("Only VIEW interactions can be posted by clients");
    }
    User currentUser = securityUtils.getCurrentUser();
    interactionService.logView(currentUser, documentId);
}
```

**What it does:** A `POST` endpoint that records an interaction resource returns `204 No Content`.

**What it should do:** `POST` endpoints that create a resource should return `201 Created`. `204` is reserved for operations that succeed with no response body, which is appropriate only for `DELETE` or `PUT`/`PATCH` with no response body. Even if no `Location` header is provided (because interactions are ephemeral), the correct status for a successful `POST` that creates a server-side record is `201`. Using `204` violates RFC 9110 §15.3.5 and misleads API clients into thinking nothing was created.

**Correct status:** `201 Created` (with or without a body).

---

### Finding 2 — `DocumentController.update` returns 200 with a raw return type, making status invisible (MEDIUM)

**File:** `DocumentController.java` lines 100–105

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

**What it does:** Returns the updated `DocumentDetail` directly as a raw type with no `ResponseEntity` wrapper and no `@ResponseStatus` annotation. Spring defaults to `200 OK`.

**What it should do:** `200 OK` is correct for a `PUT` that returns the updated resource. The issue is that without an explicit `@ResponseStatus(HttpStatus.OK)` annotation or a `ResponseEntity<DocumentDetail>` return type, the intent is invisible and it is inconsistent with the other endpoints in the same controller (e.g. `create` returns `ResponseEntity<DocumentDetail>`). Additionally, if the contract ever needs to change to `204`, there is no annotation to update.

**Correct status:** `200 OK` — but should be made explicit for consistency and readability.

---

### Finding 3 — `DocumentController.list` and `DocumentController.get` return raw types with no status annotation (MEDIUM)

**File:** `DocumentController.java` lines 52–72

```java
@GetMapping
public PageResponse<DocumentSummary> list(...) { ... }

@GetMapping("/{id}")
public DocumentDetail get(@PathVariable UUID id, ...) { ... }
```

**What they do:** Both methods return raw objects. Spring defaults to `200 OK`, which is correct. However, unlike `create`, `createArticle`, and `streamFile`, these methods do not return `ResponseEntity` or carry `@ResponseStatus`. This inconsistency makes the API contract harder to reason about and makes the codebase internally inconsistent — all other read endpoints in other controllers use `ResponseEntity.ok(...)`.

**What they should do:** Either wrap in `ResponseEntity.ok(...)` or annotate with `@ResponseStatus(HttpStatus.OK)` for consistency.

---

### Finding 4 — `UsernameNotFoundException` handler maps to `401 Unauthorized` instead of `404 Not Found` (HIGH)

**File:** `GlobalExceptionHandler.java` lines 205–210

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

**What it does:** Maps `UsernameNotFoundException` to `401 Unauthorized`.

**What it should do:** `UsernameNotFoundException` is a Spring Security exception that is thrown when `UserDetailsService.loadUserByUsername` cannot find a user. There are two distinct call sites in this codebase:

1. **Inside `JwtAuthenticationFilter.doFilterInternal` (lines 49–55):** The filter catches `UsernameNotFoundException` directly, clears the security context, and calls `entryPoint.commence(...)`, writing `401` to the servlet response and returning immediately. The exception never reaches the `GlobalExceptionHandler`. The `@ExceptionHandler(UsernameNotFoundException.class)` mapping is therefore **dead code** for this path.

2. **If `UsernameNotFoundException` ever reaches the dispatcher from a different call site** (e.g. a future service that calls `UserDetailsService` directly): mapping it to `401` is semantically wrong. A missing user account queried as a resource is a `404 Not Found`. Only authentication failure is `401`.

The real risk is that this handler creates a **false sense of security**: developers may assume all unauthenticated-user scenarios are caught here, while the actual JWT-filter path bypasses it entirely.

**Correct action:** Remove or rename the handler to clarify it is only a fallback. If kept, map to `404` (for a resource-not-found scenario) and document that it is separate from the JWT filter's 401 path.

---

### Finding 5 — `InvalidDocumentContentException` message is silently discarded (HIGH)

**File:** `InvalidDocumentContentException.java` lines 1–8

```java
public class InvalidDocumentContentException extends IllegalArgumentException {

    public InvalidDocumentContentException(String message) {
        super(message);
    }
}
```

**File:** `GlobalExceptionHandler.java` lines 184–189

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                           HttpServletRequest request) {
    log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
}
```

**What it does:** `InvalidDocumentContentException` extends `IllegalArgumentException`. When thrown, it is caught by `handleIllegalArgument`, which always responds with the hard-coded string `"Invalid request"`, discarding the specific message passed to `InvalidDocumentContentException`.

**What it should do:** `InvalidDocumentContentException` is a named, domain-specific exception. It should have its own `@ExceptionHandler` in `GlobalExceptionHandler` (or extend a custom base class) that returns `422 Unprocessable Entity` (document content is structurally present but semantically invalid) or at minimum `400 Bad Request` with the actual descriptive message preserved.

The current design also means that any future `IllegalArgumentException` thrown anywhere in the application will have its message silently replaced with `"Invalid request"`, potentially hiding real error details from callers and making debugging difficult.

**Correct status:** `422 Unprocessable Entity` (content structurally present but invalid) or `400 Bad Request`, with the actual exception message forwarded to the caller.

---

### Finding 6 — `handleIllegalArgument` discards message for sort-field validation error (MEDIUM)

**File:** `DocumentController.java` lines 134–143

```java
private void validateSort(Sort sort) {
    if (sort == null || sort.isUnsorted()) {
        return;
    }
    for (Sort.Order order : sort) {
        if (!ALLOWED_SORT.contains(order.getProperty())) {
            throw new IllegalArgumentException("Sort field not allowed: " + order.getProperty());
        }
    }
}
```

**File:** `GlobalExceptionHandler.java` lines 184–189

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                           HttpServletRequest request) {
    log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
}
```

**What it does:** `DocumentController.validateSort` constructs a detailed `IllegalArgumentException` message `"Sort field not allowed: <field>"`. The `handleIllegalArgument` handler logs the message but returns the opaque `"Invalid request"` to the API caller.

**What it should do:** The caller receives no information about which sort field was invalid. The message `ex.getMessage()` should be forwarded in the response body so that API clients can self-correct.

**Correct status:** `400 Bad Request` is correct; the response body message should be `ex.getMessage()` (or a subset of it).

---

### Finding 7 — `RecommendationController` pagination bounds violation message discarded (MEDIUM)

**File:** `RecommendationController.java` lines 39–49

```java
@GetMapping("/recommendations")
public ResponseEntity<PageResponse<DocumentSummary>> getRecommendations(Pageable pageable) {
    if (pageable.getPageSize() > MAX_PAGE_SIZE) {
        throw new IllegalArgumentException(
                "Recommendations page size exceeds the maximum of " + MAX_PAGE_SIZE);
    }
    if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
        throw new IllegalArgumentException(
                "Recommendations page number exceeds the maximum of " + MAX_PAGE_NUMBER);
    }
    ...
}
```

**What it does:** Throws `IllegalArgumentException` with a specific message, which is caught by `handleIllegalArgument` and replaced with the generic `"Invalid request"`.

**What it should do:** The caller receives no indication that they exceeded a pagination limit. The specific message should reach the caller.

---

### Finding 8 — `OwnershipService` throws `NotFoundException` inside `@PreAuthorize`, causing `403` instead of `404` (HIGH)

**File:** `OwnershipService.java` lines 28–69

```java
public boolean isDocumentOwner(UUID documentId, UserDetails principal) {
    if (principal == null) {
        return false;
    }
    Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));   // line 33
    return document.getAuthor().getEmail().equals(principal.getUsername());
}

public boolean isReadingListOwner(UUID listId, UserDetails principal) {
    if (principal == null) {
        return false;
    }
    ReadingList list = readingListRepository.findById(listId)
            .orElseThrow(() -> new ReadingListNotFoundException(listId));    // line 52
    return list.getUser().getEmail().equals(principal.getUsername());
}

public boolean isCommentOwnerOrAdmin(UUID commentId, UserDetails principal) {
    ...
    Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new CommentNotFoundException(commentId));    // line 67
    return comment.getAuthor().getEmail().equals(principal.getUsername());
}
```

**What it does:** These methods are invoked via Spring Security's `@PreAuthorize` SpEL expressions on multiple controller methods:

- `@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")` — `DocumentController.update` (line 100) and `DocumentController.delete` (line 107)
- `@PreAuthorize("@ownership.isReadingListOwner(#id, principal)")` — `ReadingListController.getReadingList`, `updateReadingList`, `deleteReadingList`, `addItem`, `removeItem`
- `@PreAuthorize("@ownership.isCommentOwnerOrAdmin(#commentId, principal)")` — `CommentController.deleteComment`

When the resource does not exist, `DocumentNotFoundException` / `ReadingListNotFoundException` / `CommentNotFoundException` (all extending `NotFoundException`) are thrown. Spring Security's method security infrastructure wraps all exceptions from `@PreAuthorize` evaluations that do not result in a clean `false` into an `AccessDeniedException`, which the `handleAccessDenied` handler maps to `403 Forbidden`.

**What it should do:** A request to update or delete a resource that does not exist should return `404 Not Found`, not `403 Forbidden`. `403` implies the resource exists but the caller lacks permission. This distinction matters for security and correctness.

The fix is to either:
1. Return `false` from the ownership methods when the resource is not found (so Spring Security returns `403`, and the controller method re-fetches and then throws `404` explicitly), or
2. Use a custom `PermissionEvaluator` that can propagate `NotFoundException` without wrapping it in `AccessDeniedException`.

**Affected endpoints:**
- `PUT /api/documents/{id}` — non-existent document → `403` instead of `404`
- `DELETE /api/documents/{id}` — non-existent document → `403` instead of `404`
- `GET /api/reading-lists/{id}` — non-existent list → `403` instead of `404`
- `PUT /api/reading-lists/{id}` — non-existent list → `403` instead of `404`
- `DELETE /api/reading-lists/{id}` — non-existent list → `403` instead of `404`
- `POST /api/reading-lists/{id}/items` — non-existent list → `403` instead of `404`
- `DELETE /api/reading-lists/{id}/items/{documentId}` — non-existent list → `403` instead of `404`
- `DELETE /api/documents/{documentId}/comments/{commentId}` — non-existent comment → `403` instead of `404`

---

### Finding 9 — `GlobalExceptionHandler` has no handler for `AccessForbiddenException` specifically (LOW)

**File:** `AccessForbiddenException.java` lines 1–8

```java
public class AccessForbiddenException extends ForbiddenException {
    public AccessForbiddenException(String message) {
        super(message, "ACCESS_FORBIDDEN");
    }
}
```

**File:** `GlobalExceptionHandler.java` lines 141–146

```java
@ExceptionHandler(ForbiddenException.class)
public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex,
                                                     HttpServletRequest request) {
    log.warn("Forbidden on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
}
```

**What it does:** `AccessForbiddenException` extends `ForbiddenException`, so it is caught by the `handleForbidden` handler and returns `403 Forbidden`. This is semantically correct.

**Assessment:** This is not a bug — the status code is correct. However, it is worth noting that `ForbiddenException` is an abstract class and the handler uses `ForbiddenException.class` as the catch type, which means all subclasses are handled. This is intentional and correct.

**Verdict:** No issue. Included here for completeness.

---

### Finding 10 — `UsernameNotFoundException` handler is dead code for the JWT-filter path (HIGH)

**File:** `JwtAuthenticationFilter.java` lines 49–55

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
    SecurityContextHolder.clearContext();
    entryPoint.commence(request, response,
            new BadCredentialsException(e.getMessage(), e));
    return;
}
```

**File:** `GlobalExceptionHandler.java` lines 205–210

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

**What it does:** The JWT filter catches `UsernameNotFoundException`, writes the `401` response directly via `JsonAuthenticationEntryPoint.commence`, and returns from the filter chain. The exception never propagates to Spring MVC's dispatcher servlet and therefore never reaches `GlobalExceptionHandler`. The `@ExceptionHandler(UsernameNotFoundException.class)` handler is dead code for this path.

**What it should do:** The handler at `GlobalExceptionHandler` line 205 should be removed or clearly documented as a fallback for non-filter call sites. Its presence creates a false impression that the handler governs authentication behaviour, which it does not.

Additionally, if `UsernameNotFoundException` were ever thrown from a service method that reaches the dispatcher (e.g. if a future developer calls `userDetailsService.loadUserByUsername` from a controller or service), mapping it to `401 Unauthorized` would be semantically wrong — it would be a `404 Not Found` scenario (the user resource does not exist), not an authentication failure.

---

### Finding 11 — `InvalidTokenException` can escape the JWT filter path to `GlobalExceptionHandler` in theory (MEDIUM)

**File:** `JwtAuthenticationFilter.java` lines 49–55

```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
```

**What it does:** The filter catches `InvalidTokenException` from `jwtService.extractClaims(token)`. This is handled correctly in the filter. However, `InvalidTokenException` is also handled in `GlobalExceptionHandler` at lines 198–203:

```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                        HttpServletRequest request) {
    log.warn("Invalid JWT token on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}
```

**Assessment:** Both handlers exist and both return `401`. If the JWT filter always catches `InvalidTokenException`, the `GlobalExceptionHandler` mapping is dead code for this exception type as well. If `JwtService` is ever called from outside the filter (e.g. from a service), the handler provides a correct fallback at `401`.

This is not a status-code bug, but it represents dead code and a misunderstanding of which layer owns the responsibility for handling JWT errors.

---

### Finding 12 — `DocumentController.streamFile` returns `404` (via `DocumentNotFoundException`) when the document has no file, conflating "not found" with "no file attached" (MEDIUM)

**File:** `DocumentService.java` lines 179–182

```java
if (document.getUploadedFilePath() == null) {
    throw new DocumentNotFoundException(id);
}
```

**What it does:** When a document exists in the database but has no associated uploaded file (e.g. it is an article-type document created via `POST /documents/article`), `streamFile` throws `DocumentNotFoundException`, which maps to `404 Not Found`.

**What it should do:** The document was found — it exists. The correct status for "this resource does not support this operation" or "this sub-resource does not exist" is `404` if treating the file as a sub-resource, but more precisely `409 Conflict` or `422 Unprocessable Entity` (the document exists but cannot produce a file stream). Returning `404` misrepresents the situation: the document exists, only the file attachment is absent. Callers receiving `404` may incorrectly infer the document itself does not exist.

A more precise approach would be a dedicated `DocumentFileNotFoundException` that maps to a `404` with a message clearly indicating the document has no file, or a `409 Conflict` with a message like `"Document {id} has no associated file"`.

**Severity:** MEDIUM — the status code (`404`) is debatable; the message (generic "Document not found") is actively misleading.

---

### Finding 13 — No `@ResponseStatus` on any exception class (LOW / Design)

**Files:** All exception classes under `com.alexandria.exception`

```
ForbiddenException.java     — no @ResponseStatus
AccessForbiddenException.java — no @ResponseStatus
NotFoundException.java       — no @ResponseStatus
ConflictException.class      — no @ResponseStatus
InvalidTokenException.java   — no @ResponseStatus
InvalidDocumentContentException.java — no @ResponseStatus
... (all subclasses)
```

**What they do:** None of the custom exception classes carry `@ResponseStatus`. All status-code mappings live exclusively in `GlobalExceptionHandler`.

**Assessment:** This is an architectural choice, not a bug. Centralising status-code mappings in `GlobalExceptionHandler` is correct and preferred over scattering `@ResponseStatus` across exception classes (which would make it harder to audit). However, the implication is that every exception class is opaque about its intended HTTP semantics, and a developer adding a new exception must remember to add a corresponding handler in `GlobalExceptionHandler`. The current codebase is consistent about this, so there is no immediate bug — but it is a fragility to be aware of.

**Verdict:** No status-code bug. Design note only.

---

## Cross-cutting observation: `IllegalArgumentException` as a catch-all swallows messages

`GlobalExceptionHandler.handleIllegalArgument` (lines 184–189) replaces the exception message with the hard-coded string `"Invalid request"`. Three separate call sites throw `IllegalArgumentException` with informative messages that are silently discarded:

1. `DocumentController.validateSort` — `"Sort field not allowed: <field>"`
2. `RecommendationController.getRecommendations` (page size) — `"Recommendations page size exceeds the maximum of 50"`
3. `RecommendationController.getRecommendations` (page number) — `"Recommendations page number exceeds the maximum of 200"`
4. `InvalidDocumentContentException` (inherits from `IllegalArgumentException`) — any document content message

In all four cases the caller receives only `"Invalid request"` with no actionable detail. The root cause is the `build(HttpStatus.BAD_REQUEST, "Invalid request", request)` call ignoring `ex.getMessage()`. The fix is to change this to `build(HttpStatus.BAD_REQUEST, ex.getMessage(), request)`, or to give each throw site its own named exception class with a specific handler.

---

## Findings by severity

### HIGH (must fix)

| # | Finding |
|---|---------|
| 1 | `POST /documents/{id}/interactions` returns `204` — should be `201` |
| 4 | `UsernameNotFoundException` mapped to `401` — semantically wrong; handler is also dead code for the JWT filter path |
| 5 | `InvalidDocumentContentException` message silently discarded by `handleIllegalArgument` |
| 8 | `OwnershipService` throws `NotFoundException` in `@PreAuthorize`, causing `403` instead of `404` for non-existent resources |
| 10 | `UsernameNotFoundException` handler in `GlobalExceptionHandler` is dead code; creates misleading picture of auth error handling |

### MEDIUM (should fix)

| # | Finding |
|---|---------|
| 2 | `DocumentController.update` / `list` / `get` return raw types with no explicit status — inconsistent |
| 6 | `handleIllegalArgument` discards sort-field validation message |
| 7 | `handleIllegalArgument` discards pagination-bounds message |
| 11 | `handleInvalidToken` handler is dead code for the JWT-filter path |
| 12 | `streamFile` returns `404` when document has no file — misleading to callers |

### LOW (nice to fix / design notes)

| # | Finding |
|---|---------|
| 13 | No `@ResponseStatus` on exception classes — architectural choice, not a bug |
