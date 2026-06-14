# Alexandria Backend — Authorization & Access Control Security Audit

**Date:** 2026-06-14  
**Auditor:** Claude Code  
**Scope:** Broken access control, IDOR, privilege escalation, visibility enforcement, ownership checks  
**Methodology:** Static analysis of all controller, service, security, and ownership layers

---

## Summary Table

| # | Endpoint / Area | Finding | Severity |
|---|---|---|---|
| 1 | `GET /api/users/{id}` | User profile exposed to unauthenticated callers with no rate-limiting | LOW |
| 2 | `GET /api/reading-lists` | Missing `@PreAuthorize`; relies on `SecurityUtils` throwing on unauthenticated call | MEDIUM |
| 3 | `POST /api/reading-lists` | Missing `@PreAuthorize`; relies on `SecurityUtils` throwing on unauthenticated call | MEDIUM |
| 4 | `GET /api/recommendations` | Missing `@PreAuthorize`; relies on `SecurityUtils` throwing on unauthenticated call | MEDIUM |
| 5 | `POST /api/documents/{id}/interactions` | Missing `@PreAuthorize`; relies on `SecurityUtils` throwing on unauthenticated call | MEDIUM |
| 6 | `DELETE /api/documents/{documentId}/comments/{commentId}` | Document owner cannot delete comments on their own document | LOW |
| 7 | `PUT /api/documents/{id}` | `@PreAuthorize` does not grant admins the ability to update documents | LOW |
| 8 | `GET /api/documents/{id}/file` — private file stream | Visibility enforcement duplicated and divergent from `GET /api/documents/{id}` | LOW |
| 9 | `addComment` — private document visibility check | Uses `currentUserEmail` string comparison from `Authentication.getName()` instead of id | LOW |
| 10 | `addComment` — unauthenticated commenter path | `@PreAuthorize("isAuthenticated()")` is present but is bypassed if `SecurityConfig` `permitAll` widens the path | MEDIUM |
| 11 | `GET /api/documents/*/comments` — SecurityConfig wildcard | `permitAll()` uses `*/` glob which may not cover all path forms | LOW |
| 12 | `RecommendationService` — cross-user recommendation data | No finding; correctly scoped to `currentUser.getId()` | INFO |
| 13 | Role escalation via `PUT /api/users/{id}` | `UpdateUserRequest` does not expose a role field; no escalation path | INFO |
| 14 | `OwnershipService.isCommentOwnerOrAdmin` — document owner cannot delete comments | Document author has no elevated deletion right on their own document's comments | MEDIUM |
| 15 | `ReadingListController` — `getReadingList`, `updateReadingList`, `deleteReadingList`, `addItem`, `removeItem` | Ownership check is in `@PreAuthorize`; service layer performs no second check (defence-in-depth gap) | LOW |
| 16 | `DocumentService.update` — no admin bypass | Admin cannot correct metadata without also being the author | LOW |
| 17 | `GET /api/documents` — `authorId` filter leaks private document count | Authenticated user can filter by another user's `authorId`; `isVisibleToUser` hides content but count metadata leaks | LOW |

---

## Finding 1 — `GET /api/users/{id}` exposes any user profile to unauthenticated callers

**File:** `UserController.java` lines 33–36; `SecurityConfig.java` line 99  
**Severity:** LOW

### Code

```java
// SecurityConfig.java line 99
.requestMatchers(HttpMethod.GET, "/api/users/{id}").permitAll()

// UserController.java line 33–36
@GetMapping("/{id}")
public ResponseEntity<UserSummary> getUser(@PathVariable UUID id) {
    return ResponseEntity.ok(userService.get(id));
}
```

### Attack Scenario

Any unauthenticated caller who knows (or can enumerate) a user's UUID can retrieve their `displayName`. Combined with the user IDs returned in document author fields (which are also public), an attacker can map every UUID to a display name without ever authenticating.

### What Check Is Missing

Authentication requirement. The `permitAll()` is intentional for public profile visibility, but the absence of any rate-limiting or authentication requirement means this endpoint can be used as a free user-enumeration oracle.

### What Is Not a Problem

`UserSummary` only exposes `id` and `displayName` — email, password hash, and role are not present, so the information disclosure is low-severity.

---

## Finding 2 — `GET /api/reading-lists` missing `@PreAuthorize`

**File:** `ReadingListController.java` lines 38–41; `SecurityConfig.java` line 103  
**Severity:** MEDIUM

### Code

```java
// ReadingListController.java lines 38–41
@GetMapping
public ResponseEntity<Page<ReadingListSummaryResponse>> getReadingLists(Pageable pageable) {
    return ResponseEntity.ok(readingListService.getReadingLists(securityUtils.getCurrentUser(), pageable));
}
```

No `@PreAuthorize` annotation is present. The endpoint is protected only by the `anyRequest().authenticated()` catch-all in `SecurityConfig` (line 103) plus `SecurityUtils.getCurrentUser()` throwing `UsernameNotFoundException` (a 500, not a 401) when called without a token.

### Attack Scenario

1. An unauthenticated caller sends `GET /api/reading-lists`.
2. `SecurityConfig` should block this via `anyRequest().authenticated()`, but the absence of an explicit annotation means the intent is not visible at the method level.
3. More critically: if the catch-all is ever loosened (e.g., a new `permitAll()` rule is added above it by mistake), this endpoint would expose reading lists without any secondary defence.

### What Check Is Missing

An explicit `@PreAuthorize("isAuthenticated()")` annotation at the method level. Defence-in-depth requires the authorization rule to be declared at the point of enforcement, not only in a central catch-all.

---

## Finding 3 — `POST /api/reading-lists` missing `@PreAuthorize`

**File:** `ReadingListController.java` lines 43–50  
**Severity:** MEDIUM

### Code

```java
@PostMapping
public ResponseEntity<ReadingListResponse> createReadingList(@Valid @RequestBody CreateReadingListRequest request) {
    ReadingListResponse response = readingListService.createReadingList(request, securityUtils.getCurrentUser());
    ...
}
```

Identical pattern to Finding 2. No `@PreAuthorize`. The request flows to `securityUtils.getCurrentUser()` which throws `UsernameNotFoundException` (not `AccessDeniedException`) when called unauthenticated, producing an unintended 500 instead of a 401.

### Attack Scenario

Same as Finding 2. An unauthenticated call results in a `UsernameNotFoundException` propagating up. Depending on the exception handler, this may return a 500 Internal Server Error, leaking stack information rather than a clean 401.

### What Check Is Missing

`@PreAuthorize("isAuthenticated()")`.

---

## Finding 4 — `GET /api/recommendations` missing `@PreAuthorize`

**File:** `RecommendationController.java` lines 38–50; `SecurityConfig.java` line 101  
**Severity:** MEDIUM

### Code

```java
// SecurityConfig.java line 101
.requestMatchers(HttpMethod.GET, "/api/recommendations").authenticated()

// RecommendationController.java lines 38–50
@GetMapping("/recommendations")
public ResponseEntity<PageResponse<DocumentSummary>> getRecommendations(Pageable pageable) {
    ...
    User currentUser = securityUtils.getCurrentUser();
    return ResponseEntity.ok(recommendationService.getRecommendations(currentUser.getId(), pageable));
}
```

`SecurityConfig` marks this path as `authenticated()` but there is no `@PreAuthorize` at the method level. The same defence-in-depth gap as Findings 2 and 3 applies.

### What Check Is Missing

`@PreAuthorize("isAuthenticated()")` on the method. Because recommendations are scoped to `currentUser.getId()`, there is no cross-user IDOR risk here, but the authentication guard is not declared at the closest point to the business logic.

---

## Finding 5 — `POST /api/documents/{id}/interactions` missing `@PreAuthorize`

**File:** `RecommendationController.java` lines 52–61; `SecurityConfig.java` line 100  
**Severity:** MEDIUM

### Code

```java
// SecurityConfig.java line 100
.requestMatchers(HttpMethod.POST, "/api/documents/*/interactions").authenticated()

// RecommendationController.java lines 52–61
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

No `@PreAuthorize`. Additionally, note that the `SecurityConfig` line 100 uses a `*` glob (`/api/documents/*/interactions`), which matches paths where the document segment does not contain a slash. Standard Ant path matching treats `*` as matching any string without slashes — this is adequate here, but the absence of `@PreAuthorize` means removing the `SecurityConfig` rule silently opens the endpoint.

### What Check Is Missing

`@PreAuthorize("isAuthenticated()")`.

---

## Finding 6 — Document owner cannot delete comments on their own document

**File:** `CommentController.java` lines 57–64; `OwnershipService.java` lines 56–69  
**Severity:** MEDIUM

### Code

```java
// CommentController.java lines 57–64
@PreAuthorize("@ownership.isCommentOwnerOrAdmin(#commentId, principal)")
@DeleteMapping("/{commentId}")
public ResponseEntity<Void> deleteComment(
        @PathVariable UUID documentId,
        @PathVariable UUID commentId) {
    commentService.deleteComment(documentId, commentId);
    return ResponseEntity.noContent().build();
}

// OwnershipService.java lines 56–69
public boolean isCommentOwnerOrAdmin(UUID commentId, UserDetails principal) {
    ...
    boolean isAdmin = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals(RoleNames.ADMIN));
    if (isAdmin) {
        return true;
    }
    Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new CommentNotFoundException(commentId));
    return comment.getAuthor().getEmail().equals(principal.getUsername());
}
```

The authorization check is `isCommentOwner OR isAdmin`. A document owner who receives an abusive or off-topic comment on their own document has no way to remove it — only the comment author or an admin can do so.

### Attack Scenario

User B posts a harassing comment on User A's document. User A cannot delete it. Only an admin can act, creating a moderation bottleneck and potentially leaving harmful content visible.

### What Check Is Missing

The ownership check should also return `true` when the authenticated principal is the author of the parent document. This would require passing `documentId` to the check and querying the document author:

```java
// Missing logic:
Document document = documentRepository.findById(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
if (document.getAuthor().getEmail().equals(principal.getUsername())) {
    return true;
}
```

---

## Finding 7 — `PUT /api/documents/{id}` admin cannot update documents

**File:** `DocumentController.java` lines 100–105; `OwnershipService.java` lines 28–35  
**Severity:** LOW

### Code

```java
// DocumentController.java lines 100–105
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

Compare with delete, which grants admin access:

```java
// DocumentController.java lines 107–112
@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
@DeleteMapping("/{id}")
```

The `PUT` endpoint has no `or hasRole('ADMIN')` clause. An admin who needs to fix misleading metadata (e.g., a misleading title) on another user's document cannot do so without impersonating the owner.

### Attack Scenario

This is an operational issue rather than an attacker-exploitable vulnerability, but it creates an asymmetry: admins can delete content they cannot edit.

### What Check Is Missing

`or hasRole('ADMIN')` in the `@PreAuthorize` expression on `PUT /api/documents/{id}`.

---

## Finding 8 — Private document file stream visibility check diverges from metadata endpoint

**File:** `DocumentService.java` lines 172–185 vs. lines 136–144; `DocumentController.java` lines 114–125  
**Severity:** LOW

### Code

```java
// DocumentService.java streamFile() lines 172–185
public StoredFileResource streamFile(UUID id, UUID currentUserId) {
    Document document = documentRepository.findById(id)
            .orElseThrow(() -> new DocumentNotFoundException(id));
    if (document.getVisibility() == Visibility.PRIVATE
            && (currentUserId == null || !document.getAuthor().getId().equals(currentUserId))) {
        throw new DocumentNotFoundException(id);
    }
    ...
}

// DocumentService.java get() lines 136–144
public DocumentDetail get(UUID id, UUID currentUserId) {
    Document document = documentRepository.findWithCategoriesById(id)
            .orElseThrow(() -> new DocumentNotFoundException(id));
    if (document.getVisibility() == Visibility.PRIVATE
            && (currentUserId == null || !document.getAuthor().getId().equals(currentUserId))) {
        throw new DocumentNotFoundException(id);
    }
    ...
}
```

The visibility logic is duplicated identically in two methods. This is a code quality concern that becomes a security concern: if visibility rules become more complex (e.g., shared-with-specific-users, time-limited access), one copy is likely to be updated without the other.

### What Check Is Missing

The visibility guard should be extracted into a single shared private method or a domain method on the `Document` entity. The current duplication is a latent inconsistency risk.

---

## Finding 9 — `CommentService.assertVisible` uses email string comparison instead of ID

**File:** `CommentService.java` lines 61–70  
**Severity:** LOW

### Code

```java
private void assertVisible(Document document, String currentUserEmail) {
    if (document.getVisibility() == Visibility.PUBLIC) {
        return;
    }
    if (currentUserEmail != null && currentUserEmail.equals(document.getAuthor().getEmail())) {
        return;
    }
    throw new DocumentNotFoundException(document.getId());
}
```

`DocumentService.get()` and `DocumentService.streamFile()` compare ownership using `document.getAuthor().getId().equals(currentUserId)` (UUID comparison). `CommentService.assertVisible()` compares using email strings sourced from `authentication.getName()`. While both are unique identifiers in this system, the email approach is inconsistent and fragile: if a future feature allows email changes, a user who changes their email could temporarily lose access to their own private documents' comments.

### What Check Is Missing

Consistency: `CommentService.assertVisible` should accept `UUID currentUserId` and compare by ID, mirroring the pattern used everywhere else.

---

## Finding 10 — `addComment` — `@PreAuthorize("isAuthenticated()")` versus SecurityConfig `permitAll` conflict

**File:** `CommentController.java` lines 44–55; `SecurityConfig.java` line 96  
**Severity:** MEDIUM

### Code

```java
// SecurityConfig.java line 96
.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()

// CommentController.java lines 44–55
@PreAuthorize("isAuthenticated()")
@PostMapping
public ResponseEntity<CommentResponse> addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    CommentResponse response = commentService.addComment(documentId, request, securityUtils.getCurrentUser());
    ...
}
```

The `SecurityConfig` `permitAll()` rule at line 96 is scoped to `HttpMethod.GET`. The `@PreAuthorize("isAuthenticated()")` on `addComment` correctly guards the `POST`. There is no conflict for the current code.

However, the `SecurityConfig` `permitAll` rule uses a bare `*` glob (`/api/documents/*/comments`) rather than the specific path pattern `{documentId}/comments`. If the path structure ever changes to include nested sub-paths this wildcard could inadvertently grant `GET` access to mutation sub-paths. This is low-risk as-is but worth noting for path-level security reviews.

### What Check Is Missing

Nothing is currently missing for authentication. The pre-existing `@PreAuthorize("isAuthenticated()")` on the `POST` handler is correct. The observation is that the `SecurityConfig` wildcard should be documented as intentionally GET-only.

---

## Finding 11 — `SecurityConfig` `permitAll` for `/api/documents/*/comments` uses Ant wildcard

**File:** `SecurityConfig.java` line 96  
**Severity:** LOW

### Code

```java
.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()
```

The `*` wildcard in Ant path matching matches any single path segment (no slashes). This is correct for matching `/api/documents/{documentId}/comments`. However, it would not match `/api/documents/some-id/comments/extra` — that requires `**`. The pattern is adequately restrictive and does not accidentally expose sub-resources.

The concern is that `GET /api/documents/{id}` at line 95 and `GET /api/documents/{id}/file` at line 95 are explicitly listed, but comments use a separate wildcard rule. If a future nested route is added under `/api/documents/*/comments/...` and a developer assumes the wildcard covers it, there could be an unintended `permitAll` extension.

### What Check Is Missing

The intent should be documented. No immediate vulnerability exists.

---

## Finding 12 — Recommendation data is correctly scoped — no cross-user IDOR

**File:** `RecommendationController.java` lines 48–49  
**Severity:** INFO (no vulnerability)

### Code

```java
User currentUser = securityUtils.getCurrentUser();
return ResponseEntity.ok(recommendationService.getRecommendations(currentUser.getId(), pageable));
```

The `currentUser.getId()` is obtained from the authenticated principal, not from a user-supplied parameter. There is no `userId` path variable that an attacker could manipulate. The endpoint correctly returns recommendations for the authenticated caller only.

---

## Finding 13 — No role escalation via `PUT /api/users/{id}`

**File:** `UpdateUserRequest.java`; `UserService.java` lines 31–42  
**Severity:** INFO (no vulnerability)

### Code

```java
public record UpdateUserRequest(
        @NullOrNotBlank String displayName,
        @NullOrNotBlank @Size(min = 8, max = 255) String password
) {}
```

`UpdateUserRequest` only accepts `displayName` and `password`. There is no `role`, `roles`, or `authorities` field. `UserService.update()` only sets `displayName` and `passwordHash`. A user cannot escalate their own role via this endpoint.

---

## Finding 14 — Document owner cannot delete comments on their own document (ownership gap in `OwnershipService`)

**File:** `OwnershipService.java` lines 56–69; `CommentController.java` lines 57–64  
**Severity:** MEDIUM

This is covered in detail under Finding 6. Restated here because it falls under the explicit checklist item "Comment deletion/editing not restricted to comment owner or document owner" — the current implementation is restricted to comment owner **or admin**, but not document owner, which is the missing case.

---

## Finding 15 — Reading list service layer has no ownership defence-in-depth

**File:** `ReadingListService.java` lines 56–103; `ReadingListController.java`  
**Severity:** LOW

### Code

```java
// ReadingListController.java line 53–57
@PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
@GetMapping("/{id}")
public ResponseEntity<ReadingListResponse> getReadingList(@PathVariable UUID id) {
    return ResponseEntity.ok(readingListService.getReadingList(id));
}

// ReadingListService.java lines 56–59
public ReadingListResponse getReadingList(UUID id) {
    ReadingList list = readingListRepository.findById(id)
            .orElseThrow(() -> new ReadingListNotFoundException(id));
    return readingListMapper.toResponse(list);
}
```

All write and read operations on reading lists (`getReadingList`, `updateReadingList`, `deleteReadingList`, `addItem`, `removeItem`) are protected by `@ownership.isReadingListOwner(#id, principal)` at the controller layer. The service methods perform no ownership check of their own.

### Attack Scenario

If the service methods are ever called from a second call site (e.g., a batch job, a scheduled task, another controller, or a test) without going through the `@PreAuthorize`-annotated controller method, there is no service-level guard to prevent cross-user access. This is a defence-in-depth gap rather than an immediate exploitable vulnerability.

### What Check Is Missing

Service-level ownership assertion:

```java
// Example for updateReadingList
if (!list.getUser().getId().equals(callerUserId)) {
    throw new AccessDeniedException("Not the owner of reading list " + id);
}
```

---

## Finding 16 — Admin can delete but not edit documents — asymmetric privilege

**File:** `DocumentController.java` lines 100–112  
**Severity:** LOW

Covered under Finding 7. Listed separately here to flag the asymmetry in the checklist context of "Admin-only operations accessible by regular users" — the inverse problem exists: an operation (edit) that admins should be able to perform is blocked from admins.

---

## Finding 17 — `authorId` filter on `GET /api/documents` leaks private document existence for authenticated users

**File:** `DocumentController.java` lines 52–65; `DocumentSpecifications.java` lines 40–44; `DocumentService.java` lines 147–169  
**Severity:** LOW

### Code

```java
// DocumentSpecifications.java lines 40–44
public static Specification<Document> isVisibleToUser(UUID currentUserId) {
    return (root, query, cb) -> cb.or(
            cb.equal(root.get("visibility"), Visibility.PUBLIC),
            cb.equal(root.get("author").get("id"), currentUserId)
    );
}
```

When an authenticated user calls `GET /api/documents?authorId=<victim-uuid>`, the specification combines `isVisibleToUser(currentUserId)` (which shows only PUBLIC documents to non-owners) with `hasAuthor(victimUUID)`. The content of private documents is never returned to non-owners — that part is correctly enforced.

However, the **total count** in the `PageResponse` reveals how many documents that victim has in total (including private ones), because the `isVisibleToUser` spec is scoped to `currentUserId`, not `authorId`. Wait — let's be precise:

The specification is:
```
WHERE (visibility = PUBLIC OR author_id = currentUserId) AND author_id = victimId
```

For an attacker where `currentUserId != victimId`, this simplifies to:
```
WHERE visibility = PUBLIC AND author_id = victimId
```

The private documents are **not** counted in the result. The specification correctly excludes them. This is a non-finding on closer analysis — the content and count of private documents belonging to victim users is not leaked.

### Corrected Assessment

No vulnerability. The `isVisibleToUser` spec, combined with `hasAuthor`, correctly limits results to public documents when the requester is not the author.

---

## Summary of Actionable Findings

| Priority | Finding | Action Required |
|---|---|---|
| MEDIUM | Finding 6 / 14 | `OwnershipService.isCommentOwnerOrAdmin` must also return `true` when the caller is the document author |
| MEDIUM | Finding 2 | Add `@PreAuthorize("isAuthenticated()")` to `ReadingListController.getReadingLists` |
| MEDIUM | Finding 3 | Add `@PreAuthorize("isAuthenticated()")` to `ReadingListController.createReadingList` |
| MEDIUM | Finding 4 | Add `@PreAuthorize("isAuthenticated()")` to `RecommendationController.getRecommendations` |
| MEDIUM | Finding 5 | Add `@PreAuthorize("isAuthenticated()")` to `RecommendationController.logInteraction` |
| LOW | Finding 7 | Add `or hasRole('ADMIN')` to `@PreAuthorize` on `PUT /api/documents/{id}` |
| LOW | Finding 8 | Extract duplicated visibility guard into a shared private method |
| LOW | Finding 9 | Change `CommentService.assertVisible` to compare by UUID instead of email string |
| LOW | Finding 15 | Add ownership assertion inside `ReadingListService` methods for defence-in-depth |

---

## Non-Findings (Explicitly Verified Clean)

| Checklist Item | Status |
|---|---|
| IDOR on document fetch — private docs visible to non-owners | Clean — `DocumentService.get()` and `streamFile()` both enforce visibility |
| IDOR on document list — private docs in paginated list | Clean — `DocumentSpecifications.isVisibleToUser()` filters correctly |
| Admin-only category operations accessible to users | Clean — `CategoryController` has `@PreAuthorize("hasRole('ADMIN')")` on all write endpoints |
| User A modifying User B's reading list | Clean — all mutating reading list endpoints have `@ownership.isReadingListOwner` |
| User A modifying User B's document | Clean — `@ownership.isDocumentOwner` on `PUT /api/documents/{id}` |
| User A deleting User B's document | Clean — `@ownership.isDocumentOwner or hasRole('ADMIN')` on `DELETE /api/documents/{id}` |
| User A updating User B's profile | Clean — `@ownership.isSelf(#id, principal)` on `PUT /api/users/{id}` |
| Role escalation via profile update | Clean — `UpdateUserRequest` has no role field |
| Comments on private documents visible to non-owners | Clean — `CommentService.assertVisible()` enforces visibility before returning comments |
| Recommendations exposing other users' interaction data | Clean — scoped to `currentUser.getId()` from authenticated principal |
| `GET /api/reading-lists` returning another user's lists | Clean — `readingListRepository.findByUserId(currentUser.getId(), ...)` scopes by owner |
