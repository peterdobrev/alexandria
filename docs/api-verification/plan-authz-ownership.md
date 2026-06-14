Now I have a complete picture of all the relevant code. Here is the implementation plan:

---

# Alexandria — Authorization & Ownership Security Fixes: Implementation Plan

**Date:** 2026-06-14
**Based on:** authz-ownership-findings.md audit report

---

## 1. Executive Summary

Two critical paths require immediate attention.

**Path 1 — Private document bookmarking bypass (Findings 10 + 14, High)**

`ReadingListService.addItem()` fetches a `Document` by ID and attaches it to a reading list with no visibility check. Any authenticated user who knows a private document's UUID can bookmark it, which:
1. Logs a BOOKMARK interaction against the private document in the interactions table.
2. Permanently stores the document reference in their reading list.
3. Exposes the full `DocumentSummary` (title, description, author, sizeBytes, contentType, visibility) on every subsequent `GET /api/reading-lists/{id}` call, because `ReadingListMapper.toItemResponse()` maps items directly from the entity with no visibility filter.

The real-world impact: an attacker who learns a private document's UUID — through a shared URL, browser history, log file, or a moment of prior access — can permanently harvest its metadata and bypass the carefully-applied masking in `DocumentService.get()` and `DocumentService.list()`. The bookmark also poisons the recommendations data.

**Path 2 — `UsernameNotFoundException` → HTTP 500 (Finding 1, High)**

`CommentController.addComment()` carries no `@PreAuthorize` and calls `securityUtils.getCurrentUser()` directly. When an unauthenticated caller hits `POST /api/documents/{id}/comments`, Spring Security's filter places a null `Authentication` in the context. `SecurityUtils.getCurrentUser()` detects null and throws `UsernameNotFoundException("No authenticated user")`. `GlobalExceptionHandler` has no handler for that type, so it falls through to `handleGeneric`, which logs an ERROR-level stack trace and returns HTTP 500. The correct status is 401. The practical impact: monitoring systems treating 5xx as service failures receive spurious alerts, and clients receive no indication they need to authenticate.

---

## 2. Prioritised Fix List

| Priority | Severity | Finding | Affected Endpoint(s) | Fix Type |
|----------|----------|---------|----------------------|----------|
| 1 | High | 1 | `POST /api/documents/{id}/comments` | New `@ExceptionHandler` in `GlobalExceptionHandler` + `@PreAuthorize` annotation on controller method |
| 2 | High | 10 + 14 | `POST /api/reading-lists/{id}/items`, `GET /api/reading-lists/{id}` | New visibility guard in `ReadingListService.addItem()` + visibility filter in `ReadingListMapper.toItemResponse()` / `toResponse()` |
| 3 | Medium | 4 + 5 + 6 (partial) | `PUT/DELETE /api/documents/{id}`, all `/api/reading-lists/{id}/**`, `DELETE /api/documents/{id}/comments/{commentId}` | Throw domain `NotFoundException` from `OwnershipService` methods when resource not found, instead of `orElse(false)` |
| 4 | Medium | 13 | `SecurityConfig` CORS | Split comma-separated origin string in `corsConfigurationSource()` |
| 5 | Medium | 3 + 11 | `POST /api/documents`, `POST /api/documents/article`, plus other write endpoints without `@PreAuthorize` | Add `@PreAuthorize("isAuthenticated()")` annotations |
| 6 | Low | 7 | `PUT /api/documents/{id}` | Add `or hasRole('ADMIN')` to `@PreAuthorize` or add an explanatory code comment |
| 7 | Low | 6 (audit log) | `DELETE /api/documents/{id}/comments/{commentId}` | Add audit log in `CommentService.deleteComment()` for admin-initiated deletions |
| 8 | Low | 16 | `JwtAuthenticationFilter` | Write JSON `ErrorResponse` body on the 401 response |
| 9 | Low | 15 | `JwtService.generateToken()` | Add `Objects.requireNonNull` guard |

---

## 3. Implementation Steps

### Fix 1 — Handle `UsernameNotFoundException` as 401 and guard `addComment` (Finding 1)

**Step 1a — `GlobalExceptionHandler.java`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/exception/GlobalExceptionHandler.java`

Add a new `@ExceptionHandler` method after the existing `handleBadCredentials` handler (line 190). This must be ordered before `handleGeneric` so Spring's dispatcher chooses it first:

```java
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                             HttpServletRequest request) {
    log.warn("Unauthenticated access on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

No signature changes. No other files affected.

**Step 1b — `CommentController.java`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/CommentController.java`

Add `@PreAuthorize("isAuthenticated()")` to `addComment()` at line 45. This provides a defence-in-depth backstop at the method level; the new `GlobalExceptionHandler` entry provides the correct 401 response if the filter somehow does not fire. Both changes together are belt-and-suspenders:

```java
@PreAuthorize("isAuthenticated()")
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    return commentService.addComment(documentId, request, securityUtils.getCurrentUser());
}
```

**Impact on existing tests:**

`CommentControllerTest` uses `MockMvcBuilders.standaloneSetup`, which does not run the Spring Security filter chain. The `@PreAuthorize` annotation is not evaluated in standalone mode, so existing tests are unaffected. A new test should be added:

```java
// CommentControllerTest — new test
@Test
void addComment_unauthenticatedUser_returns401() throws Exception {
    // SecurityUtils.getCurrentUser() throws UsernameNotFoundException
    when(securityUtils.getCurrentUser()).thenThrow(new UsernameNotFoundException("No authenticated user"));

    mockMvc.perform(post("/api/documents/{documentId}/comments", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"body\":\"test\"}"))
            .andExpect(status().isUnauthorized());
}
```

---

### Fix 2 — Block private-document bookmarking in `addItem` and filter the mapper (Findings 10 + 14)

**Step 2a — `ReadingListService.addItem()` — add visibility guard**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/ReadingListService.java`

The method signature stays the same. After the document fetch on line 78, add a visibility check. The check must compare `document.getAuthor().getId()` against `list.getUser().getId()` because the reading list already holds the `User` entity (the `list` was fetched two lines earlier):

```java
public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request) {
    ReadingList list = readingListRepository.findById(listId)
            .orElseThrow(() -> new ReadingListNotFoundException(listId));
    Document document = documentRepository.findById(request.documentId())
            .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));

    // Treat private documents owned by others as non-existent — same masking as DocumentService.get()
    if (document.getVisibility() == Visibility.PRIVATE
            && !document.getAuthor().getId().equals(list.getUser().getId())) {
        throw new DocumentNotFoundException(request.documentId());
    }

    if (readingListItemRepository.findByReadingListIdAndDocumentId(listId, request.documentId()).isPresent()) {
        throw new ReadingListItemAlreadyExistsException(listId, request.documentId());
    }
    ReadingListItem item = new ReadingListItem();
    item.setReadingList(list);
    item.setDocument(document);
    item.setAddedAt(Instant.now());
    ReadingListItemResponse response = readingListMapper.toItemResponse(readingListItemRepository.save(item));
    interactionService.logBookmark(list.getUser(), document);
    return response;
}
```

The import `com.alexandria.entity.Visibility` is already present in the file.

**Step 2b — `ReadingListMapper.toItemResponse()` — filter stale private items**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/mapper/ReadingListMapper.java`

`toResponse()` must skip items where the document is private and the reading list owner is not the document's author. This handles any items already in the database before Fix 2a is deployed, and guards against any future data inconsistencies. A `User listOwner` parameter is added to the private helper:

The mapper currently calls `toItemResponse` from `toResponse` and from `ReadingListService`. The cleanest change is to filter inside `toResponse` without changing the public `toItemResponse` signature (which is used by `addItem` after the item is already validated):

```java
public ReadingListResponse toResponse(ReadingList list) {
    UUID ownerId = list.getUser().getId();
    List<ReadingListItemResponse> items = list.getItems().stream()
            .filter(item -> isVisibleTo(item.getDocument(), ownerId))
            .map(this::toItemResponse)
            .toList();
    return new ReadingListResponse(list.getId(), list.getName(), list.getCreatedAt(), items);
}

private static boolean isVisibleTo(Document document, UUID viewerId) {
    if (document.getVisibility() == Visibility.PUBLIC) {
        return true;
    }
    return document.getAuthor().getId().equals(viewerId);
}
```

Required new imports in `ReadingListMapper.java`:
```java
import com.alexandria.entity.Document;
import com.alexandria.entity.Visibility;
import java.util.UUID;
```

**Impact on existing tests:**

`ReadingListServiceTest` — tests for `addItem` with a private document owned by a different user need to be added:

```java
@Test
void addItem_privateDocumentNotOwnedByListOwner_throwsDocumentNotFoundException() {
    UUID listId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();

    User listOwner = userWithId();
    User docAuthor = userWithId(); // different ID

    ReadingList list = new ReadingList();
    list.setUser(listOwner);

    Document document = new Document();
    document.setId(docId);
    document.setAuthor(docAuthor);
    document.setVisibility(Visibility.PRIVATE);

    when(readingListRepository.findById(any(UUID.class))).thenReturn(Optional.of(list));
    when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(document));

    assertThatThrownBy(() -> classUnderTest.addItem(listId, new AddReadingListItemRequest(docId)))
            .isInstanceOf(DocumentNotFoundException.class);
}

@Test
void addItem_privateDocumentOwnedByListOwner_succeeds() {
    UUID listId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();

    User listOwner = userWithId();

    ReadingList list = new ReadingList();
    list.setUser(listOwner);

    Document document = new Document();
    document.setId(docId);
    document.setAuthor(listOwner); // same user
    document.setVisibility(Visibility.PRIVATE);

    ReadingListItem savedItem = new ReadingListItem();
    savedItem.setDocument(document);
    savedItem.setReadingList(list);
    savedItem.setAddedAt(Instant.now());
    ReadingListItemResponse expected = new ReadingListItemResponse(
            UUID.randomUUID(), null /* DocumentSummary stub */, Instant.now());

    when(readingListRepository.findById(any(UUID.class))).thenReturn(Optional.of(list));
    when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(document));
    when(readingListItemRepository.findByReadingListIdAndDocumentId(any(), any()))
            .thenReturn(Optional.empty());
    when(readingListItemRepository.save(any())).thenReturn(savedItem);
    when(readingListMapper.toItemResponse(any())).thenReturn(expected);

    ReadingListItemResponse result = classUnderTest.addItem(listId, new AddReadingListItemRequest(docId));
    assertThat(result).isEqualTo(expected);
}
```

The existing `addItem` tests that use PUBLIC documents continue to pass without changes.

---

### Fix 3 — OwnershipService throws NotFoundException for missing resources (Findings 4, 5, 6-partial)

See Section 4 for the design decision rationale. The chosen approach is: throw `NotFoundException` from within `OwnershipService`.

**Step 3a — `OwnershipService.isDocumentOwner()`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/OwnershipService.java`

```java
import com.alexandria.exception.DocumentNotFoundException;

public boolean isDocumentOwner(UUID documentId, UserDetails principal) {
    if (principal == null) {
        return false;
    }
    Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
    return document.getAuthor().getEmail().equals(principal.getUsername());
}
```

**Step 3b — `OwnershipService.isReadingListOwner()`**

```java
import com.alexandria.exception.ReadingListNotFoundException;

public boolean isReadingListOwner(UUID listId, UserDetails principal) {
    if (principal == null) {
        return false;
    }
    ReadingList list = readingListRepository.findById(listId)
            .orElseThrow(() -> new ReadingListNotFoundException(listId));
    return list.getUser().getEmail().equals(principal.getUsername());
}
```

**Step 3c — `OwnershipService.isCommentOwnerOrAdmin()`**

The admin fast-path currently skips the DB hit entirely (correct and efficient). Only the non-admin path needs the fix:

```java
import com.alexandria.exception.CommentNotFoundException;

public boolean isCommentOwnerOrAdmin(UUID commentId, UserDetails principal) {
    if (principal == null) {
        return false;
    }
    boolean isAdmin = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals(RoleNames.ADMIN));
    if (isAdmin) {
        // Admins may moderate any comment, including on private documents — intentional design
        return true;
    }
    Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new CommentNotFoundException(commentId));
    return comment.getAuthor().getEmail().equals(principal.getUsername());
}
```

**Finding 12 masking consideration:** After this change, `isReadingListOwner` throws `ReadingListNotFoundException` (→ 404) for a missing list, and returns `false` (→ 403) for an existing list the caller does not own. This introduces the 404/403 distinction for reading lists that Finding 12 warns about. The acceptable mitigation: treat it the same way `DocumentService.get()` treats private documents — return 404 for both "not found" and "not yours". To achieve this, wrap the `@PreAuthorize` decision in `ReadingListController` with an explicit 404-or-403 collapse. The simplest approach is to catch the `NotFoundException` thrown from `isReadingListOwner` and convert ownership failure to 404 as well. However, the cleaner mechanism is to change `isReadingListOwner` to throw `ReadingListNotFoundException` for not-found, and add a post-check in the controller for the 403 case — see the controller-vs-service discussion in Section 4.

For the initial fix, accepting the 404/403 distinction is a reasonable trade-off given that list IDs are random UUIDs (128-bit entropy). Flag for follow-up if enumeration hardening is required.

**Impact on existing tests:**

`OwnershipServiceTest` currently has four tests, all for `isCommentOwnerOrAdmin`. New tests must be added for `isDocumentOwner` and `isReadingListOwner`. All four existing tests continue to pass because the admin path and the found-comment paths are unchanged. One test will need updating if it tested the missing-comment → false case (there is no such test in the current file). New tests:

```java
// isDocumentOwner — document not found
@Test
void isDocumentOwner_documentNotFound_throwsDocumentNotFoundException() {
    UUID documentId = UUID.randomUUID();
    var principal = userPrincipal("user@example.com");
    when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> classUnderTest.isDocumentOwner(documentId, principal))
            .isInstanceOf(DocumentNotFoundException.class);
}

// isReadingListOwner — list not found
@Test
void isReadingListOwner_listNotFound_throwsReadingListNotFoundException() {
    UUID listId = UUID.randomUUID();
    var principal = userPrincipal("user@example.com");
    when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> classUnderTest.isReadingListOwner(listId, principal))
            .isInstanceOf(ReadingListNotFoundException.class);
}

// isCommentOwnerOrAdmin — comment not found, non-admin
@Test
void isCommentOwnerOrAdmin_commentNotFound_nonAdmin_throwsCommentNotFoundException() {
    UUID commentId = UUID.randomUUID();
    var principal = userPrincipal("user@example.com");
    when(commentRepository.findById(commentId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> classUnderTest.isCommentOwnerOrAdmin(commentId, principal))
            .isInstanceOf(CommentNotFoundException.class);
}
```

---

### Fix 4 — CORS origin comma-split (Finding 13)

**`SecurityConfig.corsConfigurationSource()`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/security/SecurityConfig.java`

Replace line 50:

```java
// Before
config.setAllowedOrigins(List.of(allowedOrigins));

// After
import java.util.Arrays;

List<String> origins = Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
config.setAllowedOrigins(origins);
```

No signature changes. No test impact (CORS config is not covered by unit tests).

---

### Fix 5 — Add `@PreAuthorize("isAuthenticated()")` backstops (Findings 3 + 11)

**`DocumentController.create()` and `createArticle()`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/DocumentController.java`

```java
@PreAuthorize("isAuthenticated()")
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@ResponseStatus(HttpStatus.CREATED)
public ResponseEntity<DocumentDetail> create(...) { ... }

@PreAuthorize("isAuthenticated()")
@PostMapping("/article")
@ResponseStatus(HttpStatus.CREATED)
public ResponseEntity<DocumentDetail> createArticle(...) { ... }
```

For Finding 11, the same pattern applies to any write endpoints in other controllers that have no `@PreAuthorize` annotation — specifically `POST /api/reading-lists` and `GET /api/reading-lists` in `ReadingListController`. Those methods should receive `@PreAuthorize("isAuthenticated()")`. This requires reading `ReadingListController` to confirm the exact method names before editing.

No impact on existing tests — standalone MockMvc tests do not evaluate `@PreAuthorize`.

---

### Fix 6 — Admin update asymmetry (Finding 7)

**`DocumentController.update()`**

Decision: add `or hasRole('ADMIN')` to align with `delete()`, because an admin who can delete but cannot edit a malicious document title cannot moderate content short of deletion. If the product intent is different, the decision must be documented in a code comment before closing this finding.

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

---

### Fix 7 — Admin comment deletion audit log (Finding 6)

**`CommentService.deleteComment()`**

File: `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/CommentService.java`

`deleteComment` receives `documentId` and `commentId` but not the current user. The admin's identity is known at the `@PreAuthorize` level but not inside the service. The cleanest approach without a signature change is to resolve the current user inside the service using `SecurityContextHolder`, or to pass the actor as a parameter.

Recommended: add a `User moderator` parameter (consistent with `addComment` which takes a `User currentUser`). The controller would call `securityUtils.getCurrentUser()` and pass it in. This is an interface change:

```java
// CommentService
public void deleteComment(UUID documentId, UUID commentId, User currentUser) {
    Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new CommentNotFoundException(commentId));
    if (!comment.getDocument().getId().equals(documentId)) {
        throw new CommentNotFoundException(commentId);
    }
    if (!comment.getAuthor().getId().equals(currentUser.getId())) {
        // Admin-initiated moderation — log for audit trail
        log.info("Admin moderation: user {} deleted comment {} authored by {} on document {}",
                currentUser.getId(), commentId, comment.getAuthor().getId(), documentId);
    }
    commentRepository.delete(comment);
}
```

`CommentController.deleteComment()` would change from:
```java
commentService.deleteComment(documentId, commentId);
```
to:
```java
commentService.deleteComment(documentId, commentId, securityUtils.getCurrentUser());
```

**Impact on existing tests:** All existing `deleteComment` tests in `CommentControllerTest` that call `commentService.deleteComment(documentId, commentId)` must be updated to `commentService.deleteComment(eq(documentId), eq(commentId), any(User.class))`. Stubs using `doThrow` similarly need the updated matcher.

---

### Fix 8 — Consistent JSON error body for invalid tokens (Finding 16)

**`JwtAuthenticationFilter.java`**

The filter currently calls `response.setStatus(SC_UNAUTHORIZED)` and halts the chain with an empty body. Change it to write a JSON `ErrorResponse`:

```java
// In the catch(InvalidTokenException) block
response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
response.setContentType(MediaType.APPLICATION_JSON_VALUE);
ErrorResponse errorBody = new ErrorResponse(
        401,
        "Unauthorized",
        "Invalid or expired token",
        Instant.now(),
        request.getRequestURI()
);
response.getWriter().write(objectMapper.writeValueAsString(errorBody));
```

This requires injecting an `ObjectMapper` into `JwtAuthenticationFilter`. The filter is constructed in `SecurityConfig.jwtAuthenticationFilter()` — add `ObjectMapper` as a parameter there.

---

### Fix 9 — Null guard in `JwtService.generateToken()` (Finding 15)

**`JwtService.java`**

```java
public String generateToken(User user) {
    Objects.requireNonNull(user.getEmail(), "User email must not be null when generating a token");
    log.debug("Generating JWT token for user: {}", user.getEmail());
    ...
}
```

---

## 4. The OwnershipService 403-vs-404 Problem

**The two approaches:**

Option A — Service throws: `OwnershipService.isDocumentOwner()` throws `DocumentNotFoundException` when the document is absent. Spring evaluates the `@PreAuthorize` SpEL expression, the expression method throws, Spring propagates the exception to `GlobalExceptionHandler.handleNotFound()`, and the caller gets 404.

Option B — Controller checks first: the controller calls `documentService.existsById(id)` (or similar) before the ownership check. If the resource does not exist, throw 404 explicitly. Let `@PreAuthorize` only evaluate when the resource is confirmed to exist.

**Recommendation: Option A — throw from `OwnershipService`.**

Reasons:

1. **No duplication.** Every endpoint protected by `@ownership.isDocumentOwner` automatically gets the 404 behaviour. Option B requires a redundant existence check at every call site; adding a new endpoint that uses the ownership annotation will silently regress to 403 unless the developer remembers to add the explicit check.

2. **Consistent with existing domain pattern.** `DocumentService.get()` throws `DocumentNotFoundException` as a masking strategy for private documents. `OwnershipService` throwing the same exception for missing documents produces a consistent contract at the HTTP layer.

3. **Correct Spring SpEL exception propagation.** When a `@PreAuthorize` expression method throws a runtime exception (anything that is not `AccessDeniedException`), Spring Security does not swallow it — it propagates up through the filter chain to `GlobalExceptionHandler`. The 404 handler fires correctly.

4. **Simpler `@PreAuthorize` expressions.** The expressions stay clean (`@ownership.isDocumentOwner(#id, principal)`) without needing additional wrapping logic.

**The one caveat (Finding 12):** After Fix 3, `isReadingListOwner` returns `false` for an existing list the caller does not own, and throws for a missing list. This creates a 404/403 distinction, which is a minor existence oracle for list IDs. This is acceptable given UUIDs are 128-bit random, but if the product requires full masking, the `@PreAuthorize` expressions for reading list endpoints can use a combined expression that throws 404 in both cases — implemented by adding a `getOrThrow` helper to `OwnershipService` that throws `ReadingListNotFoundException` for both missing and not-owned cases. Flag as a follow-up rather than blocking the primary fix.

---

## 5. Recommended Implementation Order

The dependency order is:

**Step 1 — `GlobalExceptionHandler.java`** (Finding 1, step 1a)
Add the `UsernameNotFoundException` → 401 handler. This is a safe, additive change with no dependencies. It must land before any ownership service changes, because the ownership service changes will start throwing `NotFoundException` types from inside `@PreAuthorize` — confirming that the handler chain routes them correctly is easier to verify in isolation.

**Step 2 — `ReadingListService.addItem()` and `ReadingListMapper`** (Finding 10 + 14)
Add the visibility guard and the mapper filter. These are independent of the ownership service refactor and are the highest-impact security fixes. The mapper filter handles pre-existing stale rows.

**Step 3 — `CommentController.addComment()` `@PreAuthorize`** (Finding 1, step 1b)
Add the authentication annotation. Depends on Step 1 being deployed so the 401 handler is in place.

**Step 4 — `OwnershipService` — throw NotFoundException** (Findings 4, 5, 6-partial)
Refactor all three `orElse(false)` methods. This depends on Step 1 (the handler chain must correctly route the new exceptions). Update `OwnershipServiceTest` with the new throw-on-missing tests.

**Step 5 — `SecurityConfig` CORS split** (Finding 13)
Isolated change; no dependencies. Can be done in any order.

**Step 6 — `@PreAuthorize("isAuthenticated()")` backstops** (Findings 3 + 11)
Additive, no dependencies. Best done after the ownership service refactor so all annotation patterns are in place.

**Step 7 — Admin update `@PreAuthorize` alignment** (Finding 7)
One-line annotation change; no dependencies.

**Step 8 — Audit log for admin comment deletion** (Finding 6)
Adds a parameter to `CommentService.deleteComment()` — update controller and tests.

**Step 9 — `JwtAuthenticationFilter` JSON body** (Finding 16)
Requires `ObjectMapper` injection; update `SecurityConfig.jwtAuthenticationFilter()` bean.

**Step 10 — `JwtService.generateToken()` null guard** (Finding 15)
Trivial, no dependencies; last.

---

## 6. Acceptable As-Is

The following findings are intentional design choices or non-security items that require no code change in this plan:

**Finding 2** — The `GET`-only `permitAll` rule in `SecurityConfig` correctly uses `HttpMethod.GET`, so `POST /api/documents/{id}/comments` is not covered by it. This is correct behaviour confirmed by inspection.

**Finding 6 (admin bypass)** — `isCommentOwnerOrAdmin` granting admins blanket comment deletion rights is intentional. The fix only adds a code comment and audit log (Fix 7 above); the semantics themselves do not change.

**Finding 8** — `UserSummary` exposes only `id` and `displayName`. No sensitive data is leaked. No change required.

**Finding 9** — `logInteraction` rejecting non-VIEW kinds with 400 is the correct API contract. No change required.

**Finding 17** — No `Accept-Ranges` / partial content support in `streamFile()` is a functional gap, not a security issue. Out of scope for this plan.

**Finding 18** — The double DB lookup in `DocumentController.currentUserId()` is a performance concern, not a security issue. Out of scope.
