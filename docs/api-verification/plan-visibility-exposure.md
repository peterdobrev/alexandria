Now I have all the context needed. Here is the implementation plan:

---

# Alexandria Visibility-Exposure — Implementation Plan

## 1. Executive Summary

**Real data leakage (must fix):**

Finding 5 is the only finding that causes genuine data exfiltration today. Any authenticated user who knows or can supply a PRIVATE document UUID can call `POST /api/reading-lists/{id}/items` and receive a full `DocumentSummary` in the response — including title, description, author, categories, file metadata, and timestamps. The item is also persisted and re-leaked on every subsequent `GET /api/reading-lists/{id}`. Finding 17 is a direct downstream consequence: the interaction engine records a BOOKMARK against a document the caller has no right to access, poisoning recommendations.

**Architectural inconsistency that enables existence confirmation (fix in the same pass):**

Finding 4 is not a data leak per se, but it breaks the security model. The entire codebase uses 404 masking to prevent an unauthenticated caller from confirming that a PRIVATE document exists. `CommentService.assertVisible()` throws 403 instead, breaking that contract. Any caller can distinguish PRIVATE documents from non-existent ones via `GET /api/documents/{id}/comments`.

**Operational defect causing misleading error codes (fix next):**

Findings 9/10 are the same root cause: `UsernameNotFoundException` is not handled in `GlobalExceptionHandler`, so unauthenticated requests to `permitAll` routes that call `getCurrentUser()` produce HTTP 500 instead of 401. This affects `POST /api/documents/*/comments` in particular. This is not a data leak but will mask authentication failures in monitoring.

**Theoretical / latent risks (schedule as maintenance work):**

Findings 3, 11, 13, 14, 18 — no active data leakage today. Finding 3 is a documentation gap that risks a future regression. Finding 11 is a latent `GlobalExceptionHandler` gap. Findings 13, 14, 18 are design trade-offs with well-understood implications.

---

## 2. Prioritised Fix List

| # | Endpoint / Class | Gap | Fix | Severity | Effort |
|---|-----------------|-----|-----|----------|--------|
| 5 | `ReadingListService.addItem()` | PRIVATE documents owned by others can be bookmarked; full `DocumentSummary` returned and persisted | Visibility check before saving item; throw `DocumentNotFoundException` for non-owners | High | Small |
| 17 | `InteractionService.logBookmark()` via `addItem()` | BOOKMARK interaction recorded for PRIVATE doc owned by another user | Fixed automatically when Finding 5 is fixed — no separate change required | Medium | None (free fix) |
| 4 | `CommentService.assertVisible()` | Throws 403 for PRIVATE docs; breaks 404-masking contract used everywhere else | Replace `AccessForbiddenException` with `DocumentNotFoundException` | Medium | Trivial |
| 9 | `POST /api/documents/*/comments` in `SecurityConfig` | No `authenticated()` rule; unauthenticated call reaches service, which then throws `UsernameNotFoundException` → 500 | Add `.requestMatchers(HttpMethod.POST, "/api/documents/*/comments").authenticated()` to `SecurityConfig` | Medium | Trivial |
| 10 | `GlobalExceptionHandler` | `UsernameNotFoundException` unhandled → falls to generic 500 handler | Add `@ExceptionHandler(UsernameNotFoundException.class)` returning 401 | Medium | Trivial |
| 3 | `DocumentService.get()`, `streamFile()` | Intentional 404 masking is undocumented; at risk of future regression | Add explanatory comments in both methods | Low | Trivial |
| 11 | `GlobalExceptionHandler` | `InvalidTokenException` unhandled — latent risk | Add `@ExceptionHandler(InvalidTokenException.class)` returning 401 | Medium | Trivial |
| 18 | `SecurityConfig.corsConfigurationSource()` | Single-string `allowedOrigins` silently breaks comma-separated operator input | `List.of(allowedOrigins.split(","))` with trimming | Low | Trivial |
| 13 | `JwtService.generateToken()` | JWT subject is user email; readable from token payload without key | Document as known risk; optionally switch subject to user ID UUID | Low | Small |
| 14 | `DocumentController.streamFile()` | Non-ASCII filenames not percent-encoded per RFC 5987 | Add `filename*=UTF-8''...` encoding | Low | Small |

---

## 3. Implementation Steps

### Finding 5 — `ReadingListService.addItem()`: PRIVATE document access via reading list

**File:** `alexandria/src/main/java/com/alexandria/service/ReadingListService.java`

The method signature is `addItem(UUID listId, AddReadingListItemRequest request)`. The list owner is already fetched via `readingListRepository.findById(listId)` and available as `list.getUser()`. No signature change is needed.

After the document is fetched and before the duplicate-item check, insert:

```java
if (document.getVisibility() == Visibility.PRIVATE
        && !document.getAuthor().getId().equals(list.getUser().getId())) {
    // 404 masking: do not confirm the document exists to unauthorised callers
    throw new DocumentNotFoundException(request.documentId());
}
```

Place this block between the `documentRepository.findById(...)` call and the `readingListItemRepository.findByReadingListIdAndDocumentId(...)` call. Finding 17 is resolved automatically: `logBookmark()` is never reached when the guard throws.

**Why `list.getUser().getId()` and not `currentUserId`:** The current user and the list owner are the same entity here because all write operations on reading lists already require `authenticated()` and the controller passes `SecurityUtils.getCurrentUser()` as the list owner at creation. Using `list.getUser()` avoids adding a new parameter and is consistent with what the persistence layer already enforces.

### Finding 17 — `InteractionService.logBookmark()`

No change needed. The fix in Finding 5 prevents `logBookmark()` from being called with unauthorised documents.

### Finding 4 — `CommentService.assertVisible()`: 403 instead of 404 for PRIVATE documents

**File:** `alexandria/src/main/java/com/alexandria/service/CommentService.java`

Change the last line of `assertVisible()`:

```java
// Before:
throw new AccessForbiddenException("Access denied");

// After:
// Intentional 404 masking: throwing DocumentNotFoundException (not 403)
// prevents callers from confirming that a PRIVATE document exists.
// This is consistent with DocumentService.get() and streamFile().
throw new DocumentNotFoundException(document.getId());
```

The import of `AccessForbiddenException` can be removed if it is no longer used elsewhere in the file (verify with IDE — it is the only use site in `CommentService.java`).

### Finding 9 — `POST /api/documents/*/comments`: missing `authenticated()` rule

**File:** `alexandria/src/main/java/com/alexandria/security/SecurityConfig.java`

In `securityFilterChain`, add one line inside `authorizeHttpRequests` alongside the existing comments rule:

```java
// before:
.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()

// after:
.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()
.requestMatchers(HttpMethod.POST, "/api/documents/*/comments").authenticated()
```

This causes Spring Security to return 401 (via the configured `authenticationEntryPoint`) before the request reaches the controller, eliminating the 500 path.

### Finding 10 — `UsernameNotFoundException` falls through to 500

**File:** `alexandria/src/main/java/com/alexandria/exception/GlobalExceptionHandler.java`

Add a new handler method. Follow the existing pattern (log at WARN, return `build(...)`):

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("Authentication required on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

Add the import for `org.springframework.security.core.userdetails.UsernameNotFoundException`.

### Finding 11 — `InvalidTokenException` not handled in `GlobalExceptionHandler`

**File:** `alexandria/src/main/java/com/alexandria/exception/GlobalExceptionHandler.java`

Add alongside the `UsernameNotFoundException` handler:

```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                        HttpServletRequest request) {
    log.warn("Invalid token on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}
```

### Finding 3 — undocumented 404 masking in `DocumentService`

**File:** `alexandria/src/main/java/com/alexandria/service/DocumentService.java`

Add a one-line comment above each masking block in `get()` and `streamFile()`:

```java
// Intentional 404 masking: returning NOT_FOUND (not 403) avoids confirming
// to unauthorised callers that a PRIVATE document with this ID exists.
if (document.getVisibility() == Visibility.PRIVATE
        && (currentUserId == null || !document.getAuthor().getId().equals(currentUserId))) {
    throw new DocumentNotFoundException(id);
}
```

### Finding 18 — CORS single-origin string

**File:** `alexandria/src/main/java/com/alexandria/security/SecurityConfig.java`

```java
// Before:
config.setAllowedOrigins(List.of(allowedOrigins));

// After:
config.setAllowedOrigins(
    Arrays.stream(allowedOrigins.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .toList()
);
```

Add `import java.util.Arrays;`.

---

## 4. The `assertVisible` Consistency Problem

`DocumentService.get()` and `streamFile()` both throw `DocumentNotFoundException` (404) when a non-owner requests a PRIVATE document. `CommentService.assertVisible()` throws `AccessForbiddenException` (403) for the same condition. Both code paths are reached by the same caller on the same resource.

**Why 403 is wrong here:**

A 403 tells the caller "I know what you are asking for, and I am denying you." This leaks document existence. Any unauthenticated caller can probe any UUID by hitting `GET /api/documents/{id}/comments` — if the document is PRIVATE they get 403; if it does not exist they get 404. The two codes are distinguishable, making the UUID space explorable for existence confirmation. While UUIDs v4 are not practically guessable, this is a design defect that contradicts the explicit security decision documented (partially) in `DocumentService`.

**Why 404 masking is the correct approach:**

The correct behaviour is to respond identically to "does not exist" and "exists but you cannot see it." This is standard practice in security-sensitive APIs (GitHub, for example, returns 404 for private repositories to unauthenticated callers). The cost is that a document owner who is authenticated will get a full response while a non-owner gets 404 — this asymmetry is intentional.

**The fix:** Change `assertVisible()` in `CommentService` to throw `DocumentNotFoundException(document.getId())` as described in section 3. The `AccessForbiddenException` type exists and remains useful for other access-control scenarios (e.g., if a future endpoint needs to confirm a resource exists but deny an action on it). It should not be used for existence-masking scenarios.

---

## 5. Recommended Implementation Order

1. **Finding 5** — the only genuine data leak; fix first. One guard clause, no API surface change, no new dependencies. Finding 17 is resolved for free.
2. **Finding 4** — one-line change in `assertVisible()`; restores architectural consistency with the masking established in `DocumentService`.
3. **Finding 9** — one line in `SecurityConfig`; fixes the 500 that masks authentication failures on the comments POST route.
4. **Finding 10** — add `UsernameNotFoundException` handler to `GlobalExceptionHandler`; closes the fallback 500 path for all `permitAll` routes that call `getCurrentUser()`.
5. **Finding 11** — add `InvalidTokenException` handler to `GlobalExceptionHandler` in the same commit as Finding 10; both are handler additions to the same file.
6. **Finding 3** — add comments to `DocumentService`; protects against future regression, costs nothing.
7. **Finding 18** — CORS robustness fix; low risk, low priority, but trivial.
8. **Findings 13, 14** — schedule separately as dedicated small tasks if the team decides to address them; both require a deliberate design decision (JWT subject change, RFC 5987 filename encoding) and carry some client-compatibility risk.

---

## 6. Acceptable As-Is

The following findings from the audit are intentional design decisions that require no code change:

| Finding | Reason acceptable as-is |
|---------|-------------------------|
| 1 — Email not on `GET /api/users/{id}` or `/me` | Intentional; `UserResponse` (which includes email) exists but is unused. If the front-end ever needs the authenticated user's email, serve `UserResponse` from `/me` only. Not a security concern; only a potential usability gap. |
| 2 — PRIVATE document list filtering | Correctly implemented via `DocumentSpecifications`. No action needed. |
| 6 — No owner field in `ReadingListResponse` | Intentional information model decision. Owner data is not part of the response contract. |
| 7 — `DocumentDetail`/`DocumentSummary` author email not exposed | `AuthorSummary` maps only `id` and `displayName`. Safe by design. |
| 8 — `CommentResponse` author email not exposed | Same as Finding 7. Safe. |
| 12 — No delete user endpoint; no token revocation concern | No user deletion means no revocation gap today. Document the JWT-blocklist requirement as a prerequisite for any future user-deletion feature. |
| 15 — UUID primary keys not enumerable | UUIDs v4 are cryptographically random. No enumeration risk. |
| 16 — Recommendations do not leak other users' reading patterns | Aggregate interaction weights only; no raw user-document signals in responses. Safe. |
