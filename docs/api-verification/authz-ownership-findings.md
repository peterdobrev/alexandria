Now I have a thorough picture of every relevant path. Here is the complete security audit report.

---

# Alexandria Backend — Authorization & Ownership Security Audit

**Date:** 2026-06-14
**Auditor:** Claude Code
**Scope:** Authorization, ownership checks, privilege escalation, information leakage, authentication enforcement

---

## Finding 1 — `addComment` enforces authentication via `UsernameNotFoundException` instead of a proper 401

**Endpoint:** `POST /api/documents/{documentId}/comments`
**Severity:** High
**Location:** `CommentController.addComment()` (line 44–50), `SecurityUtils.getCurrentUser()` (line 14–22)

**Description:**

`addComment` carries no `@PreAuthorize` annotation and no rule in `SecurityConfig`. When an unauthenticated caller hits this endpoint, Spring Security sets `SecurityContextHolder`'s `Authentication` to null. `SecurityUtils.getCurrentUser()` checks for null and throws `UsernameNotFoundException("No authenticated user")`.

`UsernameNotFoundException` has no handler in `GlobalExceptionHandler`. It falls through to the catch-all `handleGeneric`, which returns **HTTP 500** with `"An unexpected error occurred"`. The correct response for an unauthenticated attempt is **HTTP 401**.

**Contrast with a properly-guarded endpoint:** endpoints covered by `anyRequest().authenticated()` or `@PreAuthorize` return 401 via Spring Security's `authenticationEntryPoint`. This path bypasses that mechanism entirely.

**Exploit scenario:**

An unauthenticated client POSTs to `POST /api/documents/{id}/comments`. They receive a 500, which:
1. Does not tell them they need to authenticate (poor UX that could mask the real problem).
2. Logs an ERROR-level stack trace server-side, polluting monitoring with false alerts.
3. If the monitoring system treats 5xx as service failures, this becomes a vector for alarming the on-call team (minor DoS-via-noise).

The comment is not created — there is no security bypass — but the error contract is wrong and this counts as broken error handling with a security dimension.

**Fix:** Add `@PreAuthorize("isAuthenticated()")` to `addComment`, or add a `POST /api/documents/*/comments` → `authenticated()` rule in `SecurityConfig`. Additionally, add a `UsernameNotFoundException` handler to `GlobalExceptionHandler` that returns 401.

---

## Finding 2 — Spring Security wildcard pattern for comments path is handled correctly

**Endpoint:** `GET /api/documents/*/comments` vs `POST /api/documents/{documentId}/comments`
**Severity:** Info (no vulnerability)
**Location:** `SecurityConfig` (lines 70, 77)

**Description:**

The question is whether the `permitAll` on `GET /api/documents/*/comments` accidentally covers `POST` on the same path.

The rule at line 70 is:

```java
.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()
```

Spring Security's `requestMatchers(HttpMethod, String...)` binds a rule to an exact HTTP method. A `POST` to `/api/documents/{id}/comments` is not matched by this rule. It falls through to `anyRequest().authenticated()` (line 77). So the GET-vs-POST distinction is enforced correctly at the `SecurityConfig` level.

The actual problem for `POST` is described in Finding 1 above: `anyRequest().authenticated()` would protect the endpoint if the JWT filter sets the `SecurityContext` correctly, but the controller bypasses that protection by not letting Spring Security run the access decision — the service method throws before any access-denied logic fires (see Finding 1 for the 500 outcome).

---

## Finding 3 — `create()` and `createArticle()` authentication relies solely on `anyRequest().authenticated()`, which is correct but fragile

**Endpoints:** `POST /api/documents` (multipart), `POST /api/documents/article`
**Severity:** Medium
**Location:** `DocumentController` (lines 73–93), `SecurityConfig` (line 77)

**Description:**

Neither `create()` nor `createArticle()` has a `@PreAuthorize` annotation. Neither path is listed explicitly in `SecurityConfig`. They are protected entirely by `anyRequest().authenticated()`.

`anyRequest().authenticated()` is enforced by Spring Security's filter chain before the controller is reached, so an unauthenticated request is stopped with 401 at the filter level — `securityUtils.getCurrentUser()` is never reached.

However, there is a subtle but real risk here: `securityUtils.getCurrentUser()` is the only line inside the method that binds the request to an identity. If a configuration error in the future moves `POST /api/documents` into `permitAll` (for example, to allow anonymous uploads in a new feature), there is no `@PreAuthorize` backstop. The pattern used for `update()` and `delete()` — an explicit `@PreAuthorize` — is the defence-in-depth approach and should be applied here too.

The current production code is not exploitable as written, but the absence of a declarative ownership/authentication annotation means a one-line `SecurityConfig` change silently opens an unauthenticated document-creation path.

**Fix:** Add `@PreAuthorize("isAuthenticated()")` to both `create()` and `createArticle()`.

---

## Finding 4 — `OwnershipService.isDocumentOwner()` returns `false` for non-existent documents, producing 403 instead of 404

**Endpoints:** `PUT /api/documents/{id}`, `DELETE /api/documents/{id}`
**Severity:** Medium
**Location:** `OwnershipService.isDocumentOwner()` (lines 24–33), `DocumentController.update()` (line 95), `DocumentController.delete()` (line 102)

**Description:**

```java
return documentRepository.findById(documentId)
        .map(Document::getAuthor)
        .map(User::getEmail)
        .map(email -> email.equals(principal.getUsername()))
        .orElse(false);   // document not found → false → 403
```

When the document does not exist, `orElse(false)` returns false. `@PreAuthorize` evaluates to `false` and Spring Security throws `AccessDeniedException`, which `GlobalExceptionHandler.handleAccessDenied` translates to **403**.

An attacker probing for valid document IDs receives:
- `404` for `GET /api/documents/{id}` on a non-existent document (correct).
- `403` for `PUT /api/documents/{id}` on a non-existent document (information leak — reveals that the requester is authenticated but not the owner, distinguishable from "document does not exist").

The distinction leaks one bit of information: a valid UUID that returns 403 on `DELETE` might exist as someone else's document, whereas a completely unknown UUID returns 403 for the same reason (missing). This is a minor oracle for document existence enumeration.

More practically, the semantic is wrong: a well-designed REST API should return 404 when the addressed resource does not exist, regardless of who is asking.

**Fix:** Change `isDocumentOwner` to throw `DocumentNotFoundException` when the document is not found, or move the existence check into the controller before evaluating ownership.

---

## Finding 5 — `OwnershipService.isReadingListOwner()` returns `false` for non-existent lists, producing 403 instead of 404

**Endpoints:** `GET /api/reading-lists/{id}`, `PUT /api/reading-lists/{id}`, `DELETE /api/reading-lists/{id}`, `POST /api/reading-lists/{id}/items`, `DELETE /api/reading-lists/{id}/items/{documentId}`
**Severity:** Medium
**Location:** `OwnershipService.isReadingListOwner()` (lines 45–53)

**Description:** Identical pattern to Finding 4. A request to a non-existent list UUID returns 403 rather than 404, making it impossible for a caller to distinguish "this list does not exist" from "you do not own this list". The service layer's own `ReadingListNotFoundException` is never reached because `@PreAuthorize` fires first and returns 403.

**Fix:** Same as Finding 4 — throw `ReadingListNotFoundException` from `isReadingListOwner` when the list is not found, before the ownership comparison.

---

## Finding 6 — `isCommentOwnerOrAdmin` admin bypass is intentional and correct, but deletes comments on PRIVATE documents without visibility re-check

**Endpoint:** `DELETE /api/documents/{documentId}/comments/{commentId}`
**Severity:** Low
**Location:** `OwnershipService.isCommentOwnerOrAdmin()` (lines 56–69), `CommentService.deleteComment()` (lines 53–60)

**Description:**

The admin bypass in `isCommentOwnerOrAdmin` is intentional and appropriate — an admin should be able to moderate any comment. The implementation is correct: admin check happens before the DB hit, which is also efficient.

However, `CommentService.deleteComment()` does not call `assertVisible`. An admin can delete a comment on a PRIVATE document they do not own. This is by design given admin privileges, but the code contains no comment explaining this intentional access grant, and there is no audit log entry for admin-initiated comment deletion. If the intention were that admins can only delete on PUBLIC documents, this is a bug.

**Secondary issue:** `isCommentOwnerOrAdmin` calls `commentRepository.findById(commentId).orElse(false)` — a non-existent comment returns `false` (403) instead of 404. Same pattern as Findings 4 and 5.

**Fix:** Document the admin access intent with a code comment. Add logging in `deleteComment` when the deleting user is not the comment's author (admin moderation audit trail). Address the 403-vs-404 on non-existent comment IDs if desired.

---

## Finding 7 — Admins cannot update documents; only the owner can

**Endpoint:** `PUT /api/documents/{id}`
**Severity:** Low (design question)
**Location:** `DocumentController.update()` (line 95)

**Description:**

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
```

versus `delete()`:

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
```

Admins can delete any document but cannot update any document they do not own. This asymmetry is unusual: typically if an admin role exists to manage content, they need both operations. As written, an admin cannot fix a malicious document title or change its visibility to PRIVATE; they can only delete it entirely.

Whether this is a security gap depends on product intent. If admins are supposed to be able to demote or correct documents, this is a missing privilege. If the design intentionally limits admin mutation power to deletion only, it should be documented.

**No exploit path exists from this gap alone**, but the inconsistency is a latent misconfiguration risk.

**Fix:** Either add `or hasRole('ADMIN')` to `update()`'s `@PreAuthorize`, or add a code comment explaining why admins cannot update.

---

## Finding 8 — `GET /api/users/{id}` is public and the `UserSummary` DTO is safe

**Endpoint:** `GET /api/users/{id}`
**Severity:** Info (no vulnerability)
**Location:** `UserController.getUser()` (line 33), `UserSummary.java` (line 5)

**Description:**

`UserSummary` is:

```java
public record UserSummary(UUID id, String displayName) {}
```

It contains only `id` and `displayName`. It does not include `email`, `passwordHash`, `createdAt`, or any role information. The mapper `UserMapper.toSummary` confirms only these two fields are set.

The `UserResponse` record (used nowhere in a public endpoint) does include `email` and `createdAt`, but it is only instantiated in `UserMapper.toResponse()` which is not called from any controller.

**No sensitive data is exposed.** The public endpoint is safe as implemented.

---

## Finding 9 — `logInteraction` rejects non-VIEW kinds with 400 `IllegalArgumentException`: correct behaviour

**Endpoint:** `POST /api/documents/{id}/interactions`
**Severity:** Info (no vulnerability)
**Location:** `RecommendationController.logInteraction()` (lines 56–58)

**Description:**

```java
if (request.kind() != InteractionKind.VIEW) {
    throw new IllegalArgumentException("Only VIEW interactions can be posted by clients");
}
```

`GlobalExceptionHandler.handleIllegalArgument` maps `IllegalArgumentException` → 400 with the exception's message. This is the correct approach: the client sent an invalid request; 400 is the right status. 422 (Unprocessable Entity) would also be acceptable but 400 is standard for business-rule violations on input.

Silently ignoring a non-VIEW kind would be worse: it would accept a semantically invalid request without telling the client. Returning 400 with a clear message is the right API contract.

The message `"Only VIEW interactions can be posted by clients"` is slightly information-disclosing (reveals that BOOKMARK exists as a client-inaccessible kind), but this is negligible.

---

## Finding 10 — `ReadingListService.addItem()` does NOT check document visibility; PRIVATE documents can be bookmarked by any authenticated user

**Endpoint:** `POST /api/reading-lists/{id}/items`
**Severity:** High
**Location:** `ReadingListService.addItem()` (lines 75–90)

**Description:**

```java
Document document = documentRepository.findById(request.documentId())
        .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
// No visibility check here
ReadingListItem item = new ReadingListItem();
item.setDocument(document);
...
interactionService.logBookmark(list.getUser(), document);
```

Any authenticated user who knows (or guesses) the UUID of a PRIVATE document they do not own can:
1. Add it to their reading list — `addItem` does not call `assertVisible` or any equivalent check.
2. Trigger `interactionService.logBookmark`, logging a BOOKMARK interaction against the private document in the interactions table.
3. Retrieve the `ReadingListItemResponse`, which embeds a full `DocumentSummary` including `title`, `description`, `type`, `visibility`, `author`, `sizeBytes`, `contentType`, and `categories`.

This is a **privilege escalation / access control bypass**: authenticated users can read metadata of PRIVATE documents they do not own by bookmarking them.

**Contrast:** `DocumentService.get()` and `streamFile()` both enforce the visibility check and throw `DocumentNotFoundException` for PRIVATE documents owned by others. The reading list path entirely skips this check.

**Note on UUID guessing:** UUIDs are type-4 random (128-bit entropy), so brute-force guessing is computationally infeasible. However, the UUID of a private document could be leaked through other means (browser history, shared URL, log files, or an attacker who previously had access). Once known, the ID can be exploited through this path.

**Fix:**

```java
// In ReadingListService.addItem(), after fetching the document:
if (document.getVisibility() == Visibility.PRIVATE
        && !document.getAuthor().getId().equals(list.getUser().getId())) {
    throw new DocumentNotFoundException(document.getId());
}
```

---

## Finding 11 — Defence-in-depth for non-`permitAll` write endpoints is adequate but relies on `anyRequest().authenticated()` as the sole filter-level backstop

**Endpoints:** `PUT/DELETE /api/categories/{id}`, `PUT/DELETE /api/reading-lists/**`, `PUT /api/users/{id}`, etc.
**Severity:** Low (design note)
**Location:** `SecurityConfig` (line 77)

**Description:**

All write endpoints not explicitly listed in `SecurityConfig` are protected by `anyRequest().authenticated()`. For endpoints that carry `@PreAuthorize("hasRole('ADMIN')")` or `@PreAuthorize("@ownership.*")`, the method-level annotation provides a second layer. However, endpoints such as `POST /api/reading-lists` and `GET /api/reading-lists` have no `@PreAuthorize` annotation and rely entirely on `anyRequest().authenticated()` plus `securityUtils.getCurrentUser()`.

The risk is the same as Finding 3: a `SecurityConfig` edit that accidentally moves these paths to `permitAll` would drop authentication enforcement entirely, with no annotation backstop.

**Fix:** Apply `@PreAuthorize("isAuthenticated()")` to any method that accepts requests from the current user but has no stronger ownership annotation.

---

## Finding 12 — Reading list ID enumeration returns 403, not 404, for other users' lists (oracle)

**Endpoint:** `GET /api/reading-lists/{id}`
**Severity:** Low
**Location:** `OwnershipService.isReadingListOwner()`, `ReadingListController.getReadingList()`

**Description:**

This is a consequence of Finding 5. When user B sends `GET /api/reading-lists/{id}` with a valid UUID that belongs to user A:
- `isReadingListOwner` finds the list (it exists), compares owners, returns `false`.
- Spring Security throws `AccessDeniedException` → 403.

When user B guesses a UUID that does not exist:
- `isReadingListOwner` finds nothing, returns `false` (same code path as above via `orElse(false)`).
- Spring Security throws `AccessDeniedException` → 403.

Both cases currently return 403, so this endpoint does not distinguish "exists but not yours" from "does not exist". This actually **accidentally mitigates** the enumeration oracle described here. However, if Finding 5 is fixed by throwing `ReadingListNotFoundException` for missing lists, care must be taken: this will introduce a 404/403 distinction that creates a true existence oracle for list IDs.

**When fixing Finding 5**, ensure that reading list access returns 404 (not 403) for both "not found" and "not owned" cases (i.e., mask the response for unauthorized access, just as `DocumentService.get()` masks private documents with `DocumentNotFoundException`). The reading list service already throws `ReadingListNotFoundException` from the service layer, but that code is never reached because `@PreAuthorize` fires first.

---

## Finding 13 — CORS `allowCredentials=true` with a single-value origin from config; safe as long as `APP_CORS_ALLOWED_ORIGINS` is not `*`

**Location:** `SecurityConfig.corsConfigurationSource()` (lines 48–57)

**Severity:** Medium (conditional)

**Description:**

```java
config.setAllowedOrigins(List.of(allowedOrigins));
config.setAllowCredentials(true);
```

`allowedOrigins` is a single `String` read from `${APP_CORS_ALLOWED_ORIGINS:http://localhost:4200}`. The default is `http://localhost:4200`, which is safe.

RFC 6454 / the Fetch spec prohibit `Access-Control-Allow-Origin: *` combined with `Access-Control-Allow-Credentials: true`. Spring Security's `CorsConfiguration` enforces this: if you pass `"*"` to `setAllowedOrigins`, it throws `IllegalArgumentException` at startup when `allowCredentials` is also `true`. So an operator who accidentally sets `APP_CORS_ALLOWED_ORIGINS=*` will get a startup crash rather than a silently misconfigured server.

However, if `APP_CORS_ALLOWED_ORIGINS` is set to a comma-separated list (e.g., `http://a.com,http://b.com`), the code passes that entire string as a single element of the `List`, resulting in the origin `"http://a.com,http://b.com"` being registered — which never matches any browser origin. This would silently break all CORS for legitimate callers.

**Fix:** Split `allowedOrigins` on comma and trim whitespace:

```java
List<String> origins = Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
config.setAllowedOrigins(origins);
```

---

## Finding 14 — PRIVATE documents are correctly masked in `get()` and `list()`, but `ReadingListItemResponse` leaks `DocumentSummary` for private documents

**Endpoints:** `GET /api/documents` (list), `GET /api/documents/{id}`, `GET /api/reading-lists/{id}`, `POST /api/reading-lists/{id}/items`
**Severity:** High (overlaps with Finding 10)
**Location:** `DocumentService.list()` (lines 148–168), `ReadingListService.addItem()`, `ReadingListMapper`

**Description:**

`DocumentService.list()` applies `isVisibleToUser(currentUserId)` or `isPublic()` based on authentication. Private documents owned by others are correctly excluded from listings.

`DocumentService.get()` correctly throws `DocumentNotFoundException` for private documents accessed by non-owners, masking existence.

However, as established in Finding 10, once a private document is added to a reading list:
- `GET /api/reading-lists/{id}` returns `ReadingListResponse`, which includes a list of `ReadingListItemResponse` objects.
- Each `ReadingListItemResponse` contains a full `DocumentSummary` including `title`, `description`, `visibility`, `author`, `sizeBytes`, `contentType`.

The `ReadingListMapper.toResponse()` maps items directly from the entity without any visibility filter. So a user who successfully exploits Finding 10 (bookmarking a private document) can then permanently retrieve its metadata via their reading list endpoint.

**Fix:** Both Finding 10 (block the add) and this path (filter the output) should be addressed. Even after fixing Finding 10, if old invalid bookmarks exist in the database, `getReadingList` should skip or redact items where the document is private and the reading list owner is not the document's author.

---

## Finding 15 — `JwtService.generateToken()` called on a user with null email would NPE inside the JWT builder, not a custom exception

**Location:** `JwtService.generateToken()` (line 30), `User.java`
**Severity:** Low

**Description:**

```java
public String generateToken(User user) {
    log.debug("Generating JWT token for user: {}", user.getEmail());
    return Jwts.builder()
            .subject(user.getEmail())   // NPE if email is null
            ...
```

`User.email` is declared `@Column(nullable = false)` on the JPA entity, so a null email in a persisted user is a database constraint violation, not a normal runtime state. In practice, `AuthService.register()` validates the request with `@NotBlank @Email`, so `email` is always non-null when a token is generated.

However, if `generateToken` were called programmatically with a `new User()` (in a test or future code path), it would throw `NullPointerException` from inside the JJWT builder, which is not caught and would propagate as a 500 through `handleGeneric`.

**Fix:** Add a null check at the top of `generateToken`:

```java
Objects.requireNonNull(user.getEmail(), "User email must not be null when generating a token");
```

---

## Finding 16 — `InvalidTokenException` is not handled in `GlobalExceptionHandler`; falls through to 500

**Location:** `GlobalExceptionHandler.java`, `JwtAuthenticationFilter.java` (line 46)
**Severity:** Low

**Description:**

`InvalidTokenException` is caught inside `JwtAuthenticationFilter.doFilterInternal` (line 46) and results in a `response.setStatus(SC_UNAUTHORIZED)` with an empty body — the filter chain is halted, no JSON error body is written. This is correct for the primary path (filter-level rejection).

However, `InvalidTokenException` could also be thrown during startup by `JwtService`'s constructor (when the key is too weak). That is a configuration error, not a runtime exception, and does not need an HTTP handler.

There is no code path where `InvalidTokenException` reaches `GlobalExceptionHandler` at runtime — the filter catches it first. The annotation in the snapshot description that it "falls through to 500" is technically accurate only if somehow the exception escapes the filter (which the code prevents), but the actual runtime behaviour for an invalid token is a 401 with no JSON body rather than the structured `ErrorResponse` that all other error paths return.

The inconsistency is that all other 4xx/5xx errors return a JSON `ErrorResponse` body, but an invalid token returns `401` with an empty body. This diverges from the error contract and may confuse API clients that expect JSON.

**Fix:** In `JwtAuthenticationFilter`, write a JSON error body on the 401 response, consistent with `ErrorResponse`. Alternatively, let the exception bubble and add a handler in `GlobalExceptionHandler` that maps it to 401 with a JSON body (but this requires not swallowing it in the filter).

---

## Finding 17 — `DocumentController.streamFile()` returns the full file body in a single `200 OK`; no `Content-Range` / `Accept-Ranges` support

**Endpoint:** `GET /api/documents/{id}/file`
**Severity:** Info (not a security issue)
**Location:** `DocumentController.streamFile()` (lines 109–120)

**Description:** This is a functional gap rather than a security issue. The endpoint sends the entire file with `ResponseEntity.ok()` (200), no `Accept-Ranges: bytes` header, and no range-request handling. For large PDFs (up to 50 MB), this means clients cannot resume partial downloads. Not a security concern.

---

## Finding 18 — `DocumentController.currentUserId()` makes a second DB call when principal is non-null

**Location:** `DocumentController.currentUserId()` (lines 122–127)
**Severity:** Info

**Description:**

```java
private Optional<UUID> currentUserId(UserDetails principal) {
    if (principal == null) {
        return Optional.empty();
    }
    return Optional.of(securityUtils.getCurrentUser().getId());
}
```

When `principal` is non-null, this calls `securityUtils.getCurrentUser()` which hits `userRepository.findByEmail()`. `principal` (a `UserDetails`) already has the username (email), but not the UUID. The double DB lookup is a performance issue, not a security issue. However, if `UserDetails` were extended to carry the UUID (as a `CustomUserDetails` wrapping the `User` entity), this round-trip could be eliminated. Not a security finding.

---

## Summary Table

| # | Severity | Endpoint / Location | Issue |
|---|----------|---------------------|-------|
| 1 | **High** | `POST /api/documents/{id}/comments` | Unauthenticated call throws `UsernameNotFoundException` → 500 instead of 401 |
| 2 | Info | `GET/POST /api/documents/*/comments` | HTTP method distinction in `SecurityConfig` is correctly handled |
| 3 | **Medium** | `POST /api/documents`, `POST /api/documents/article` | No `@PreAuthorize` backstop; auth relies solely on `anyRequest().authenticated()` |
| 4 | **Medium** | `PUT/DELETE /api/documents/{id}` | Non-existent document returns 403 instead of 404 (existence oracle) |
| 5 | **Medium** | `GET/PUT/DELETE /api/reading-lists/{id}`, `POST /{id}/items` | Non-existent reading list returns 403 instead of 404 (existence oracle) |
| 6 | **Low** | `DELETE /api/documents/{id}/comments/{commentId}` | Admin bypass is correct but undocumented; no audit log; non-existent comment returns 403 not 404 |
| 7 | **Low** | `PUT /api/documents/{id}` | Admins can delete but not update documents; asymmetry undocumented |
| 8 | Info | `GET /api/users/{id}` | `UserSummary` exposes only `id` + `displayName`; no sensitive data |
| 9 | Info | `POST /api/documents/{id}/interactions` | Rejecting non-VIEW kinds with 400 is correct |
| 10 | **High** | `POST /api/reading-lists/{id}/items` | No visibility check — any authenticated user can bookmark a PRIVATE document they do not own, leaking its metadata |
| 11 | **Low** | All write endpoints without `@PreAuthorize` | Defence-in-depth gap: no annotation backstop if `SecurityConfig` is misconfigured |
| 12 | **Low** | `GET /api/reading-lists/{id}` | 403 for non-existent lists accidentally suppresses enumeration; fixing Finding 5 must preserve masking |
| 13 | **Medium** | `SecurityConfig` CORS | `APP_CORS_ALLOWED_ORIGINS` silently broken if set to a comma-separated list; `*` would crash startup |
| 14 | **High** | `GET /api/reading-lists/{id}` | `ReadingListItemResponse` embeds `DocumentSummary` for private documents without a visibility filter |
| 15 | **Low** | `JwtService.generateToken()` | Null email would produce unhandled `NullPointerException` rather than a safe error |
| 16 | **Low** | `JwtAuthenticationFilter` | Invalid token returns 401 with empty body, inconsistent with the JSON `ErrorResponse` contract used everywhere else |
| 17 | Info | `GET /api/documents/{id}/file` | No `Accept-Ranges` / partial content support (functional, not security) |
| 18 | Info | `DocumentController.currentUserId()` | Unnecessary second DB lookup per request (performance, not security) |

---

## Priority Action Items

**Critical/High — fix immediately:**

1. **Finding 10 + 14**: Add a visibility check in `ReadingListService.addItem()` before attaching the document. Also filter `ReadingListMapper` output to skip/redact items where the document is private and the reader is not the owner.
2. **Finding 1**: Add a `UsernameNotFoundException` handler to `GlobalExceptionHandler` returning 401, and add `@PreAuthorize("isAuthenticated()")` to `CommentController.addComment()`.

**Medium — fix in current sprint:**

3. **Finding 4 + 5**: Throw domain `NotFoundException` from `isDocumentOwner`/`isReadingListOwner` when the resource does not exist, so callers get 404 rather than 403.
4. **Finding 13**: Split the `APP_CORS_ALLOWED_ORIGINS` config value on commas.
5. **Finding 3**: Add `@PreAuthorize("isAuthenticated()")` to `create()` and `createArticle()`.

**Low — address in backlog:**

6. **Finding 7**: Document or align the admin update/delete asymmetry.
7. **Finding 6**: Add an audit log entry when an admin deletes a comment they did not author.
8. **Finding 16**: Align the invalid-token error response to return a JSON body.
9. **Finding 15**: Add null guard in `JwtService.generateToken()`.
