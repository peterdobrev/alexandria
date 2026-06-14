I now have a complete picture of the codebase. Here is the full audit report.

---

# Alexandria API — Security & Data Exposure Audit

## Finding 1: Email Not Exposed on Public User Endpoint

**Severity: Info (Non-Finding)**

`GET /api/users/{id}` returns `UserSummary(UUID id, String displayName)`. The `UserMapper.toSummary()` method explicitly maps only `id` and `displayName` — email is not included. The `UserResponse` record (which does include email) is defined but is unused by any controller; it is dead code. Email is safe.

`GET /api/users/me` calls the same `UserService.get()` method and returns the identical `UserSummary` DTO. There is no data available in `/me` that is not available in `/{id}`. A user cannot learn their own email, `createdAt`, roles, or any other profile fields via the API. This may be a usability gap if the front-end ever needs to display the authenticated user's email address — `UserResponse` exists but is never served.

---

## Finding 2: PRIVATE Document List Filtering Is Correct

**Severity: Info (Non-Finding)**

`DocumentSpecifications.isVisibleToUser(currentUserId)` generates:

```sql
WHERE visibility = 'PUBLIC' OR author_id = :currentUserId
```

This correctly shows the authenticated user all PUBLIC documents plus only their own PRIVATE documents. An authenticated user cannot see another user's PRIVATE documents through the list endpoint. The unauthenticated path uses `isPublic()` which restricts to `visibility = 'PUBLIC'` only. Correct.

---

## Finding 3: PRIVATE Document — 404 Masking on GET /{id} Is Intentional but Undocumented

**Severity: Low**

`DocumentService.get()` throws `DocumentNotFoundException` (404) when a PRIVATE document is requested by a non-owner. This is a deliberate security measure: a 403 would confirm the document exists, leaking metadata. A 404 treats the document as non-existent to unauthorized callers.

The behaviour is consistent: `streamFile()` applies the same masking logic. However there is no code comment or API documentation (OpenAPI `@ApiResponse`) marking this as intentional. A future developer seeing a 404 thrown for a found entity may "fix" it to a 403, breaking the masking. The discrepancy is also visible to an owner: they get a full `DocumentDetail`, but a non-owner gets a 404 for the same URL.

**Recommendation:** Add a comment in `DocumentService.get()` and `streamFile()` explaining the intentional 404 masking. Document in OpenAPI that 404 means "not found or not accessible."

---

## Finding 4: Comment Visibility Enforcement Is Architecturally Inconsistent

**Severity: Medium**

`GET /api/documents/{documentId}/comments` is `permitAll` in `SecurityConfig`. However, `CommentService.assertVisible()` throws `AccessForbiddenException` (403) for PRIVATE documents when the caller is unauthenticated or is not the document owner.

The problem: `permitAll` in the security filter does not mean "allow all." It means "do not require authentication at the filter layer." The service layer then raises a 403 via `AccessForbiddenException extends ForbiddenException`, which is caught by `GlobalExceptionHandler` and returns HTTP 403.

This is functionally correct for access control, but it is architecturally inconsistent:

- All other PRIVATE document access uses 404 masking. Comments for the same PRIVATE document return 403, which leaks that the document exists.
- The route `/api/documents/{documentId}/file` uses 404 masking; the comments route on the same document uses 403. An unauthenticated attacker can distinguish PRIVATE documents from non-existent ones by calling the comments endpoint.

**Data leaked:** The existence of a PRIVATE document can be confirmed by any unauthenticated caller who guesses its UUID (UUIDs are not guessable in practice, but the behavioural inconsistency is a design defect).

**Recommendation:** Change `assertVisible` in `CommentService` to throw `DocumentNotFoundException` instead of `AccessForbiddenException` for non-owner access to PRIVATE documents, aligning with the masking used everywhere else.

---

## Finding 5: ReadingListService.addItem() Allows Bookmarking PRIVATE Documents Owned by Others

**Severity: High**

`ReadingListService.addItem()` fetches the document by ID with a plain `documentRepository.findById()` and performs no visibility check:

```java
Document document = documentRepository.findById(request.documentId())
        .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
// No visibility check here
```

An authenticated user who knows (or guesses) the UUID of a PRIVATE document owned by someone else can add it to their reading list. The operation succeeds silently.

**Consequences:**
1. The user gains a `ReadingListItemResponse` containing a full `DocumentSummary` of the PRIVATE document (title, description, type, visibility, author, categories, hasFile, hasBody, sizeBytes, contentType, createdAt, updatedAt). This is a data leak.
2. `interactionService.logBookmark()` is called, recording a BOOKMARK interaction against the PRIVATE document for the unauthorized user. This pollutes the recommendation engine's interaction data.
3. If the item is later retrieved via `GET /api/reading-lists/{id}`, the `ReadingListResponse` embeds a full `DocumentSummary` of the PRIVATE document — data that the user should not see persists in their reading list.

**Who can access it:** Any authenticated user who can guess or enumerate a PRIVATE document UUID.

**Recommendation:** Add a visibility check in `addItem()` before saving the item:

```java
if (document.getVisibility() == Visibility.PRIVATE
        && !document.getAuthor().getId().equals(currentUser.getId())) {
    throw new DocumentNotFoundException(document.getId()); // maintain 404 masking
}
```

Note: `addItem` currently receives no `currentUser` — it receives only a `listId`. The list owner's user entity is available via `list.getUser()` and should be used for this check.

---

## Finding 6: ReadingListResponse Does Not Expose Owner User Data

**Severity: Info (Non-Finding)**

`ReadingListResponse(UUID id, String name, Instant createdAt, List<ReadingListItemResponse> items)` — no owner field. `ReadingListSummaryResponse(UUID id, String name, Instant createdAt)` — also no owner. Owner data is not exposed in responses. Items embed `DocumentSummary` (see Finding 5 regarding PRIVATE documents in that list).

---

## Finding 7: DocumentDetail and DocumentSummary — Author Email Not Exposed

**Severity: Info (Non-Finding)**

Both `DocumentDetail` and `DocumentSummary` embed `AuthorSummary(UUID id, String displayName)`. `DocumentMapper.toAuthorSummary()` maps only `id` and `displayName` from the `User` entity. Email, password hash, createdAt, and roles are not included. Safe.

---

## Finding 8: CommentResponse — Author Email Not Exposed

**Severity: Info (Non-Finding)**

`CommentResponse(UUID id, AuthorSummary author, String body, Instant createdAt)`. `AuthorSummary` contains only `id` and `displayName`. No email exposure.

---

## Finding 9: addComment() Has No @PreAuthorize — Relies on Service-Level Auth Check

**Severity: Medium**

`CommentController.addComment()` has no `@PreAuthorize` annotation and no explicit `authenticated` rule in `SecurityConfig` for `POST /api/documents/*/comments`. Authentication is enforced entirely by `SecurityUtils.getCurrentUser()` inside the service method.

If an unauthenticated caller hits `POST /api/documents/{documentId}/comments`:
- `SecurityUtils.getCurrentUser()` calls `SecurityContextHolder.getContext().getAuthentication()`, which returns `null` for an unauthenticated request on a `permitAll` route.
- `getCurrentUser()` throws `UsernameNotFoundException("No authenticated user")`.
- `UsernameNotFoundException` is **not handled** by `GlobalExceptionHandler` — it falls through to the generic `Exception` handler and returns **HTTP 500** instead of 401.

**Data exposed:** None directly. The defect is that the authentication failure produces a misleading 500 error rather than a 401, which could mask authentication issues in logs and confuse clients.

**Recommendation:** Add `.requestMatchers(HttpMethod.POST, "/api/documents/*/comments").authenticated()` to `SecurityConfig`, or add `@PreAuthorize("isAuthenticated()")` to `addComment()`. Also add `UsernameNotFoundException` handling to `GlobalExceptionHandler` returning 401.

---

## Finding 10: UsernameNotFoundException Falls Through to 500

**Severity: Medium**

`GlobalExceptionHandler` does not handle `UsernameNotFoundException`. Any path through the code that calls `SecurityUtils.getCurrentUser()` when no user is authenticated will throw `UsernameNotFoundException`, which is caught by the generic `Exception` handler and returns HTTP 500 with the message "An unexpected error occurred." The log at `ERROR` level will contain the email if it was found in the token but not in the database (deleted user with valid JWT).

This affects: `CommentController.addComment()`, `DocumentController.create()`, `DocumentController.createArticle()`, `ReadingListController` (all write operations), `RecommendationController`.

For routes already protected by Spring Security filter rules (e.g., `authenticated()`), an unauthenticated request is rejected at the filter with a 401 before reaching the service. But for routes marked `permitAll` at the filter layer where the service layer then calls `getCurrentUser()`, the 500 surfaces.

**Recommendation:** Add `@ExceptionHandler(UsernameNotFoundException.class)` to `GlobalExceptionHandler` returning 401.

---

## Finding 11: InvalidTokenException Falls Through to 500

**Severity: Medium**

`JwtService.extractClaims()` throws `InvalidTokenException`. This is caught in `JwtAuthenticationFilter` and handled correctly (returns 401 with empty body via `response.sendError()`). However, `InvalidTokenException` is **not** handled in `GlobalExceptionHandler`. If `InvalidTokenException` were ever thrown from a path outside the filter (it currently is only thrown in `JwtService` constructor for a weak key), it would produce a 500. Also, the constructor-time `InvalidTokenException` for a weak key would propagate during application startup as an `InvalidTokenException`, which would not be mapped.

The startup case is likely fatal and acceptable. But the gap in `GlobalExceptionHandler` is still a latent risk for any future use of the exception.

**Recommendation:** Add `@ExceptionHandler(InvalidTokenException.class)` returning 401.

---

## Finding 12: No Delete User Endpoint — No Token Revocation Issue

**Severity: Info (Non-Finding)**

There is no `DELETE /api/users/{id}` endpoint. User deletion is not implemented in `UserController` or `UserService`. Token revocation is therefore not a current concern, as there is no mechanism by which a user could be deleted.

**Note:** If user deletion is ever added, JWT tokens issued before deletion will remain valid until expiry since there is no token blocklist. This is a known architectural property of stateless JWTs and should be documented when that feature is built.

---

## Finding 13: JWT Subject Is User Email — Known Privacy Risk

**Severity: Low**

`JwtService.generateToken()` uses `subject(user.getEmail())`. The JWT payload (the middle base64 segment) is not encrypted — it is only signed. Anyone who obtains the token (from a log, a network capture, a browser's local storage, or a bug report) can decode the payload and read the user's email address without any key material.

`AuthResponse` returns only the raw `token` string with no `type` field and no `expiresIn` field. Clients have no programmatic way to know when the token expires without decoding it.

This is a design choice with well-known trade-offs, not a misconfiguration. However it is undocumented.

**Recommendation:** Document this as a known risk in architecture notes. Consider using a UUID or internal user ID as the JWT subject instead of the email address, which would prevent email extraction from captured tokens. At minimum, add `expiresIn` to `AuthResponse` so clients can manage token lifecycle without decoding.

---

## Finding 14: File Streaming — Content-Disposition Sanitization Analysis

**Severity: Low**

`DocumentController.streamFile()` builds the header:

```java
"inline; filename=\"" + sanitize(sfr.originalFilename()) + "\""
```

The `sanitize()` method in `DocumentController` replaces `\`, `/`, `"`, control characters below `0x20`, and `DEL (0x7F)` with `_`.

**Gaps identified:**

1. **No `filename*` (RFC 5987) encoding.** The `filename` parameter is unquoted ASCII only. Non-ASCII characters (e.g., Cyrillic, CJK, accented characters in uploaded filenames) pass through `sanitize()` unchanged since they are all above `0x7F`. This means the header could contain multi-byte UTF-8 sequences in a context that RFC 7230 does not allow for header field values (only printable US-ASCII is permitted in header values). Most browsers are tolerant of this, but it is technically malformed. A filename like `résumé.pdf` would produce `Content-Disposition: inline; filename="résumé.pdf"` which may be misinterpreted.

2. **No CRLF injection via high bytes.** The sanitizer correctly strips all bytes below `0x20` (which covers `\r` and `\n`), so classic CRLF header injection is blocked.

3. **Semicolons not stripped.** A filename like `file; type=text/html` would produce `Content-Disposition: inline; filename="file; type=text/html"`. Because the value is inside double quotes, the semicolon is not a parameter delimiter per RFC 6266. The enclosing quotes protect against this.

4. **The double-quote character is replaced by `_`**, so the quoted-string cannot be broken by the filename itself.

**Summary:** The sanitizer is effective against the most critical injection vectors (CRLF injection, quote-breaking). The non-ASCII passthrough is a standards compliance issue rather than a security vulnerability under typical browser behaviour.

**Recommendation:** Percent-encode non-ASCII characters using `filename*=UTF-8''...` (RFC 5987) for standards compliance. The sanitizer is otherwise adequate.

---

## Finding 15: User IDs Are UUIDs — Not Enumerable

**Severity: Info (Non-Finding)**

All primary keys (`User.id`, `Document.id`, `ReadingList.id`, etc.) use `@GeneratedValue(strategy = GenerationType.UUID)`. UUIDs v4 are cryptographically random and not sequentially guessable. There is no integer ID exposed in any API response. User enumeration via ID guessing is not feasible.

---

## Finding 16: Recommendations Endpoint Does Not Leak Other Users' Reading Patterns

**Severity: Info (Non-Finding)**

`RecommendationService.getRecommendations()` returns a `PageResponse<DocumentSummary>`. The response contains only document data (title, description, author display name, categories, etc.). The underlying SQL queries operate on aggregated interaction weights — they never return raw interaction data, other users' IDs, or behavioural signals. The response does not reveal which specific users interacted with which documents.

---

## Finding 17: Interaction Logging on PRIVATE Documents Owned by Others (logView)

**Severity: Medium**

`InteractionService.logView()` is called from `RecommendationController.logInteraction()`. It does apply a visibility check via `assertVisible()`, which throws `DocumentNotFoundException` for PRIVATE documents owned by others. This path is correctly protected.

However, `logBookmark()` — called from `ReadingListService.addItem()` — has no visibility check of its own. It relies on `addItem()` to have already validated access. Because `addItem()` has no visibility check (Finding 5), `logBookmark()` will be called with PRIVATE documents owned by other users, recording interaction data it should not record.

---

## Finding 18: CORS Configuration Accepts a Single Origin String as a List

**Severity: Low**

`SecurityConfig.corsConfigurationSource()` does:

```java
config.setAllowedOrigins(List.of(allowedOrigins));
```

`allowedOrigins` is a single `String` injected from `${app.cors.allowed-origins}`. This sets exactly one allowed origin. If the property ever contains a comma-separated list (a common operator mistake), the entire comma-separated string is treated as a single invalid origin rather than multiple origins. No cross-origin CORS request from any valid origin would succeed, and the error would be silent from the operator's perspective.

This is a configuration robustness issue, not a security vulnerability (it errs on the side of being more restrictive), but it is a common operational footgun.

**Recommendation:** Split the property value on commas: `List.of(allowedOrigins.split(","))` with trimming.

---

## Summary Table

| # | Finding | Severity | Endpoint(s) Affected |
|---|---------|----------|----------------------|
| 4 | Comment endpoint returns 403 for PRIVATE docs, exposing document existence (inconsistent with 404 masking elsewhere) | Medium | GET /api/documents/{id}/comments |
| 5 | `addItem()` allows bookmarking PRIVATE documents owned by others; full DocumentSummary returned | **High** | POST /api/reading-lists/{id}/items |
| 9 | `addComment()` unauthenticated path returns 500 instead of 401 | Medium | POST /api/documents/{id}/comments |
| 10 | `UsernameNotFoundException` unhandled → 500 on permitAll routes requiring auth | Medium | Multiple |
| 11 | `InvalidTokenException` unhandled in GlobalExceptionHandler | Medium | (latent) |
| 13 | JWT subject is user email; readable from token without key | Low | POST /api/auth/register, /login |
| 14 | Non-ASCII filenames pass through sanitizer into Content-Disposition header | Low | GET /api/documents/{id}/file |
| 17 | `logBookmark()` records interactions for PRIVATE docs (downstream of Finding 5) | Medium | POST /api/reading-lists/{id}/items |
| 18 | CORS single-origin string not split; comma-separated list silently fails | Low | All /api/** |
| 3 | 404 masking intentional but undocumented; risk of future "fix" breaking it | Low | GET /api/documents/{id}, /{id}/file |
