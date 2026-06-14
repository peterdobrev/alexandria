Now I have all the context needed to produce the plan. Let me write it.

---

# Alexandria API Contract — Implementation Plan

## 1. Executive Summary

The audit identified 21 findings across the Alexandria REST API. They fall into three tiers:

**Real RFC violations (must fix):**
- Findings 5/10: Two different JSON pagination shapes for paginated endpoints — clients cannot share a pagination handler. This is a breaking contract inconsistency.
- Finding 7: JWT auth failures return an empty body (or HTML). Every other error returns `{"status":…,"error":…,"message":…,"timestamp":…,"path":…}`. Clients must fork their error-handling logic.
- Finding 12: Ownership guard returns 403 for non-existent resources. RFC 7231 says 404 when the resource does not exist; 403 when it exists but access is denied. Returning 403 leaks resource existence.
- Finding 16: `addItem` does not enforce document visibility. A user can confirm that a private document with a given UUID exists by adding it to a reading list — information disclosure.

**REST convention violations (should fix):**
- Findings 1, 3, 4, 11: 201 responses with no `Location` header, or with a hardcoded relative URI instead of an absolute one derived from the request context.
- Finding 15: `addComment` authentication is invisible at the controller and security-config layer; it is buried inside the service.
- Findings 13, 14: `InvalidTokenException` and `UsernameNotFoundException` not mapped in `GlobalExceptionHandler` — latent 500s.
- Finding 17: Private document's comment endpoint returns 403; direct document access returns 404 for the same condition.
- Finding 8: `AuthResponse` is missing `token_type` and `expires_in`, which OAuth2/RFC 6750 consumers expect.

**Stylistic inconsistencies (low priority, no runtime impact):**
- Finding 2: Dead `@ResponseStatus(CREATED)` alongside `ResponseEntity.created(…)`.
- Finding 6: `DocumentController.update()` returns raw type; all other updates return `ResponseEntity.ok(…)`.
- Finding 9: `DocumentController.delete()` uses `@ResponseStatus(NO_CONTENT)`; all others use `ResponseEntity.noContent().build()`.
- Findings 18, 19, 20, 21: Range-request support absent, PATCH semantics via PUT, OpenAPI 401/403 undocumented.

**Acceptable as-is (no change needed):**
- `PUT /api/documents/{id}` returning 200 — valid REST choice; 200 vs 204 for updates is a style decision, not a violation.
- `POST /api/documents/*/interactions` returning 204 — logging an interaction is a side-effect-only action with no created resource; 204 is correct.

---

## 2. Prioritised Fix List

| # | Finding | Endpoint / Class | Issue | Fix | Severity | Effort |
|---|---------|-----------------|-------|-----|----------|--------|
| A | 5, 10 | `CommentController`, `ReadingListController` | `Page<T>` vs `PageResponse<T>` | Migrate all to `PageResponse<T>` | High | Medium |
| B | 7 | `SecurityConfig`, `JwtAuthenticationFilter` | Auth errors return empty body | Custom `AuthenticationEntryPoint` writing JSON `ErrorResponse` | High | Small |
| C | 12 | `OwnershipService` | Non-existent resource → 403 instead of 404 | Throw `NotFoundException` subclass when resource absent | High | Small |
| D | 16 | `ReadingListService.addItem` | Private doc UUID enumerable via reading list | Enforce document visibility check before adding | High | Small |
| E | 1 | `DocumentController.create` | Hardcoded relative Location URI | Replace with `ServletUriComponentsBuilder` | Medium | Trivial |
| F | 3 | `CommentController.addComment` | 201 missing Location header | Return `ResponseEntity.created(location).body(…)` | Medium | Trivial |
| G | 4, 11 | `ReadingListController.addItem` | 201 missing Location header | Return `ResponseEntity.created(location).body(…)` | Medium | Trivial |
| H | 8 | `AuthResponse`, `AuthService`, `JwtService` | Missing `token_type`, `expires_in` | Add fields; expose expiration from `JwtService` | Medium | Small |
| I | 13 | `GlobalExceptionHandler` | `InvalidTokenException` unhandled → 500 | Add `@ExceptionHandler(InvalidTokenException.class)` → 401 | Medium | Trivial |
| J | 14 | `GlobalExceptionHandler` | `UsernameNotFoundException` unhandled → 500 | Add `@ExceptionHandler(UsernameNotFoundException.class)` → 401 | Medium | Trivial |
| K | 15 | `SecurityConfig` | `addComment` auth invisible | Add `.requestMatchers(POST, "/api/documents/*/comments").authenticated()` | Medium | Trivial |
| L | 17 | `CommentService.assertVisible` | Private doc comments → 403; direct doc → 404 | Throw `DocumentNotFoundException` instead of `AccessForbiddenException` | Medium | Trivial |
| M | 2 | `DocumentController.createArticle` | Dead `@ResponseStatus(CREATED)` | Remove `@ResponseStatus(CREATED)` | Low | Trivial |
| N | 6 | `DocumentController.update` | Raw return type | Return `ResponseEntity.ok(detail)` | Low | Trivial |
| O | 9 | `DocumentController.delete` | `@ResponseStatus(NO_CONTENT)` | Change to `ResponseEntity.noContent().build()` | Low | Trivial |
| P | 18 | `DocumentController.streamFile` | No range-request support | Add `Accept-Ranges: none` header as minimum | Low | Trivial |
| Q | 19 | `SecurityConfig` CORS | PUT used with PATCH semantics | Evaluate and document explicitly; no code change unless PATCH route added | Low | Design |
| R | 20 | `OpenApiConfig` | 401/403 not in OpenAPI | Global `OperationCustomizer` or per-method `@ApiResponse` | Low | Small |

---

## 3. Implementation Steps per Finding

### Finding 1 — Hardcoded relative Location URI in `DocumentController`

**File:** `DocumentController.java`

The `create` method (file upload, line 73) and `createArticle` method (line 85) both use `URI.create("/api/documents/" + detail.id())`.

**Before:**
```java
return ResponseEntity
        .created(URI.create("/api/documents/" + detail.id()))
        .body(detail);
```

**After (both methods):**
```java
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(detail.id())
        .toUri();
return ResponseEntity.created(location).body(detail);
```

Remove `import java.net.URI;` from `DocumentController` (it will no longer be used after all three fixes below are applied). Add `import org.springframework.web.servlet.support.ServletUriComponentsBuilder;`.

---

### Finding 2 — Dead `@ResponseStatus(CREATED)` on `createArticle`

**File:** `DocumentController.java`, line 86.

Remove the `@ResponseStatus(HttpStatus.CREATED)` annotation. The `ResponseEntity.created(…)` already sets the 201 status. Also remove `import org.springframework.http.HttpStatus;` if it becomes unused after all cleanup in this file.

---

### Finding 3 — `addComment` missing Location header

**File:** `CommentController.java`

**Before:**
```java
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    return commentService.addComment(documentId, request, securityUtils.getCurrentUser());
}
```

**After:**
```java
@PostMapping
public ResponseEntity<CommentResponse> addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    CommentResponse response = commentService.addComment(documentId, request, securityUtils.getCurrentUser());
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(response.id())
            .toUri();
    return ResponseEntity.created(location).body(response);
}
```

Add `import org.springframework.web.servlet.support.ServletUriComponentsBuilder;` and `import java.net.URI;`. Remove `import org.springframework.http.HttpStatus;` if unused. `CommentResponse` must expose an `id()` accessor — verify it does (it is almost certainly a record or has a getter).

---

### Finding 4 / Finding 11 — `addItem` missing Location header

**File:** `ReadingListController.java`

The item is addressable at `DELETE /api/reading-lists/{id}/items/{documentId}`, so the document ID serves as the item identifier.

**Before:**
```java
@PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
@ResponseStatus(HttpStatus.CREATED)
@PostMapping("/{id}/items")
public ReadingListItemResponse addItem(@PathVariable UUID id,
                                       @Valid @RequestBody AddReadingListItemRequest request) {
    return readingListService.addItem(id, request);
}
```

**After:**
```java
@PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
@PostMapping("/{id}/items")
public ResponseEntity<ReadingListItemResponse> addItem(@PathVariable UUID id,
                                                       @Valid @RequestBody AddReadingListItemRequest request) {
    ReadingListItemResponse response = readingListService.addItem(id, request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{documentId}")
            .buildAndExpand(response.documentId())
            .toUri();
    return ResponseEntity.created(location).body(response);
}
```

`ReadingListItemResponse` must expose a `documentId()` accessor. Verify this in the record/class definition. Remove the now-unused `import org.springframework.http.HttpStatus;` if applicable. `java.net.URI` is already imported in this controller; verify it remains (it is used by `createReadingList` — but that method uses `ServletUriComponentsBuilder` not `URI.create`, so `URI` import may already be present only as the return type of `toUri()`).

---

### Finding 5 / Finding 10 — Pagination wrapper consolidation

See Section 4 below for the full consolidation plan.

---

### Finding 6 — `DocumentController.update()` raw return type

**File:** `DocumentController.java`

**Before:**
```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

**After:**
```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
@PutMapping("/{id}")
public ResponseEntity<DocumentDetail> update(@PathVariable UUID id,
                                             @Valid @RequestBody UpdateDocumentRequest request) {
    return ResponseEntity.ok(documentService.update(id, request));
}
```

---

### Finding 7 — Auth errors return empty body

**File:** New class `com/alexandria/security/JsonAuthenticationEntryPoint.java`, plus wiring in `SecurityConfig`.

Create a new class:

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
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                "Unauthenticated",
                Instant.now(),
                request.getRequestURI()
        );
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
```

Wire it in `SecurityConfig`. Inject `ObjectMapper` and expose the entry point as a `@Bean` in `AppConfig` or `SecurityConfig`:

```java
// In AppConfig or SecurityConfig — add ObjectMapper injection
@Bean
public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return new JsonAuthenticationEntryPoint(objectMapper);
}
```

Update the `exceptionHandling` in `SecurityConfig.securityFilterChain`:

```java
// Before:
.exceptionHandling(ex -> ex.authenticationEntryPoint(
        (_, res, _) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))

// After:
.exceptionHandling(ex -> ex
        .authenticationEntryPoint(jsonAuthenticationEntryPoint)
        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
```

Also create `JsonAccessDeniedHandler` mirroring the above pattern but returning 403, so access-denied errors also return JSON instead of the default Spring Security HTML page.

Note: `JwtAuthenticationFilter` currently calls `response.setStatus(SC_UNAUTHORIZED)` and returns with an empty body when `InvalidTokenException` is caught. After adding `JsonAuthenticationEntryPoint`, it is cleaner to let the filter call `jsonAuthenticationEntryPoint.commence(request, response, ex)` directly — or wrap the exception in a Spring `AuthenticationException` and let it propagate into the entry point. The simplest approach for now: keep the filter's early return but have it write the JSON body itself using the same `ObjectMapper`.

---

### Finding 8 — `AuthResponse` missing `token_type` and `expires_in`

See Section 5 below for the full `AuthResponse` enhancement.

---

### Finding 9 — `DocumentController.delete()` uses `@ResponseStatus(NO_CONTENT)`

**File:** `DocumentController.java`

**Before:**
```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable UUID id) {
    documentService.delete(id);
}
```

**After:**
```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable UUID id) {
    documentService.delete(id);
    return ResponseEntity.noContent().build();
}
```

---

### Finding 12 — `OwnershipService` conflates "not found" with "not owner"

**File:** `OwnershipService.java`

Change each `orElse(false)` call on the repository lookups to throw the appropriate `NotFoundException` subclass, so Spring propagates 404 when the resource does not exist and `false` (→ 403) only when it exists but belongs to someone else.

**`isDocumentOwner` — before:**
```java
return documentRepository.findById(documentId)
        .map(Document::getAuthor)
        .map(User::getEmail)
        .map(email -> email.equals(principal.getUsername()))
        .orElse(false);
```

**After:**
```java
Document document = documentRepository.findById(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
return document.getAuthor().getEmail().equals(principal.getUsername());
```

Apply the same pattern to `isReadingListOwner`:
```java
ReadingList list = readingListRepository.findById(listId)
        .orElseThrow(() -> new ReadingListNotFoundException(listId));
return list.getUser().getEmail().equals(principal.getUsername());
```

And `isCommentOwnerOrAdmin`:
```java
// Admin short-circuit remains unchanged.
Comment comment = commentRepository.findById(commentId)
        .orElseThrow(() -> new CommentNotFoundException(commentId));
return comment.getAuthor().getEmail().equals(principal.getUsername());
```

`isSelf` in `OwnershipService` should similarly throw `UserNotFoundException` instead of `orElse(false)`.

Impact: `@PreAuthorize` expressions that currently return `false` for non-existent resources will now propagate a `NotFoundException`. Spring Security wraps exceptions thrown inside `@PreAuthorize` SpEL evaluation in an `AccessDeniedException`, which would again produce 403. To ensure 404 propagates correctly, the `NotFoundException` must escape Spring Security's wrapper. The correct mechanism is to register the exception in Spring Security's exception translation chain, or to perform the existence check in the service layer instead of the `@PreAuthorize` expression.

**Recommended approach:** Keep the `@PreAuthorize` guard for ownership (returns true/false), but add a separate existence check at the top of the service method that throws `NotFoundException` before the ownership check ever runs:

```java
// In DocumentService.update():
Document document = documentRepository.findById(id)
        .orElseThrow(() -> new DocumentNotFoundException(id));
// @PreAuthorize already ran; if we get here the caller is the owner.
```

This means the service unconditionally throws 404 on missing resource, and `@PreAuthorize` only fires when the resource exists (owner check returns true/false → 200 or 403). `OwnershipService` can then revert to `orElse(false)` since 404 is raised by the service before the SpEL expression is re-evaluated on the second hit. This is the cleanest separation: service = existence; ownership = authorization.

---

### Finding 13 — `InvalidTokenException` unhandled

**File:** `GlobalExceptionHandler.java`

Add after the `BadCredentialsException` handler:

```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                         HttpServletRequest request) {
    log.warn("Invalid token on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}
```

Add `import com.alexandria.exception.InvalidTokenException;`.

---

### Finding 14 — `UsernameNotFoundException` unhandled

**File:** `GlobalExceptionHandler.java`

Add:

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                             HttpServletRequest request) {
    log.warn("Authenticated user not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authenticated user not found", request);
}
```

Add `import org.springframework.security.core.userdetails.UsernameNotFoundException;`.

---

### Finding 15 — `addComment` auth invisible at controller/config layer

**File:** `SecurityConfig.java`

The existing security rule block already contains `.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()`. Add the POST rule immediately before `.anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.POST, "/api/documents/*/comments").authenticated()
```

The service-layer `securityUtils.getCurrentUser()` call remains as a defense-in-depth assertion, but enforcement now also occurs at the filter level, consistently with every other write endpoint.

---

### Finding 16 — `addItem` does not check document visibility

**File:** `ReadingListService.java`, method `addItem`.

After the document lookup, add a visibility check identical to the one in `DocumentService.get()`:

```java
Document document = documentRepository.findById(request.documentId())
        .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));

// Enforce visibility masking: treat private documents owned by others as non-existent.
boolean isOwner = document.getAuthor().getId().equals(list.getUser().getId());
if (document.getVisibility() != Visibility.PUBLIC && !isOwner) {
    throw new DocumentNotFoundException(request.documentId());
}
```

This mirrors `DocumentService.get()` exactly: a private document that the caller does not own is surfaced as a 404.

---

### Finding 17 — Private doc comments return 403; direct doc returns 404

**File:** `CommentService.java`, `assertVisible` method.

**Before:**
```java
private void assertVisible(Document document, String currentUserEmail) {
    if (document.getVisibility() == Visibility.PUBLIC) {
        return;
    }
    if (currentUserEmail != null && currentUserEmail.equals(document.getAuthor().getEmail())) {
        return;
    }
    throw new AccessForbiddenException("Access denied");
}
```

**After:**
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

This aligns the comment endpoint's masking behavior with `DocumentController.get()`: a private document's comments are a 404, not a 403, for callers who cannot see the document.

Remove `import com.alexandria.exception.AccessForbiddenException;` if it becomes unused in this class.

---

### Finding 18 — `streamFile` returns 200 with no range-request signal

**File:** `DocumentController.java`, `streamFile` method.

Minimal fix — signal to clients that range requests are not supported:

```java
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(sfr.contentType()))
        .contentLength(sfr.sizeBytes())
        .header(HttpHeaders.ACCEPT_RANGES, "none")
        .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + sanitize(sfr.originalFilename()) + "\"")
        .body(sfr.resource());
```

A full 206 implementation is out of scope for this plan (requires `ResourceRegion` streaming, multi-range parsing, and `Content-Range` header management).

---

### Finding 19 — PUT with PATCH semantics in `UpdateUserRequest`

No code change in this plan. The fields on `UpdateUserRequest` that are nullable (`displayName`, `password`) should be treated as "omit means keep existing value" with explicit documentation. If a future iteration adds PATCH endpoints, add `"PATCH"` to `config.setAllowedMethods(…)` in `SecurityConfig.corsConfigurationSource()`.

---

### Finding 20 — OpenAPI 401/403 not documented

Register global responses via SpringDoc in `OpenApiConfig`:

```java
@Bean
public OperationCustomizer globalResponseCustomizer() {
    return (operation, handlerMethod) -> {
        operation.getResponses().addApiResponse("401",
                new ApiResponse().description("Unauthenticated"));
        operation.getResponses().addApiResponse("403",
                new ApiResponse().description("Forbidden"));
        return operation;
    };
}
```

---

## 4. Pagination Wrapper Consolidation

**Decision: migrate everything to `PageResponse<T>`.**

Rationale:
- `PageResponse<T>` is already used by `DocumentController`, the most important endpoint.
- Its shape is clean and stable: `{content, page, size, totalElements, totalPages, last}`. Spring's `Page<T>` exposes unstable internal fields (`pageable`, `sort`, `empty`, `first`, `numberOfElements`, `number`, `offset`, `paged`, `unpaged`) that are implementation details of the Spring Data layer.
- `PageResponse.of(Page<T>)` factory already exists and maps all needed fields.

**Affected controllers:**

| Controller | Method | Current type | Target type |
|------------|--------|-------------|------------|
| `CommentController.getComments` | `GET /api/documents/{documentId}/comments` | `ResponseEntity<Page<CommentResponse>>` | `ResponseEntity<PageResponse<CommentResponse>>` |
| `ReadingListController.getReadingLists` | `GET /api/reading-lists` | `ResponseEntity<Page<ReadingListSummaryResponse>>` | `ResponseEntity<PageResponse<ReadingListSummaryResponse>>` |

**Migration steps:**

1. In `CommentController.getComments`: change return type to `ResponseEntity<PageResponse<CommentResponse>>`. The service returns `Page<CommentResponse>` — wrap it at the controller boundary: `ResponseEntity.ok(PageResponse.of(commentService.getComments(…)))`.

2. In `ReadingListController.getReadingLists`: same pattern — `ResponseEntity.ok(PageResponse.of(readingListService.getReadingLists(…)))`.

3. Remove `import org.springframework.data.domain.Page;` from both controllers (only needed if `Pageable` is the only remaining dependency — keep `Pageable` import if present).

4. Add `import com.alexandria.dto.common.PageResponse;` to both controllers.

5. The service methods (`CommentService.getComments`, `ReadingListService.getReadingLists`) continue to return `Page<T>` internally. Only the controller boundary changes — this keeps the service layer free of presentation concerns.

6. This is a **breaking change** for any existing API client relying on `Page<T>` JSON shape. The `pageable`, `sort`, `first`, `empty` fields will disappear. Coordinate with frontend/consumer teams before deploying.

---

## 5. AuthResponse Enhancement

**Decision: add `tokenType` (serialised as `token_type`) and `expiresIn` (serialised as `expires_in`) to `AuthResponse`.**

`JwtService` already holds `expiration` (milliseconds, from `jwt.expiration` property, currently 21600000 ms = 6 hours). Expose it with a getter so `AuthService` can read it without re-injecting the config value.

**Step 1 — Add `getExpirationSeconds()` to `JwtService`:**

```java
public long getExpirationSeconds() {
    return expiration / 1000;
}
```

`expiration` is already a field; this is a pure read.

**Step 2 — Change `AuthResponse` from a record to a class (or keep as record with additional fields):**

Since `AuthResponse` is immutable and has no mutable state, a record with additional fields is fine:

```java
package com.alexandria.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthResponse(
        String token,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn
) {}
```

**Step 3 — Update `AuthService` to use the new constructor:**

```java
// In both register() and login():
return new AuthResponse(
        jwtService.generateToken(user),
        "Bearer",
        jwtService.getExpirationSeconds()
);
```

**Backward-compatibility note:** Adding new fields to a JSON response is additive and non-breaking for existing clients that ignore unknown fields (which all well-behaved JSON clients do). The `token` field remains at the same JSON key. No existing client breaks.

**Why not hardcode `token_type` in `AuthResponse`?** Because a record field with a constant default conveys intent cleanly and is testable. `"Bearer"` is hardcoded in the record constructor call in `AuthService`, which is the right place — the service knows it is issuing Bearer tokens; the DTO does not need to know.

**Why expose `expiresIn` from `JwtService` rather than re-injecting `${jwt.expiration}` into `AuthService`?** `AuthService` already depends on `JwtService`; adding a second injection of the same config value would duplicate knowledge. A `getExpirationSeconds()` method on `JwtService` is the single source of truth.

---

## 6. Recommended Implementation Order

The order below minimises merge conflicts and ensures each step is independently testable:

1. **Findings 13, 14** — Add missing `@ExceptionHandler` entries to `GlobalExceptionHandler`. Smallest, most isolated change. No dependencies.

2. **Finding 7** — `JsonAuthenticationEntryPoint` + `JsonAccessDeniedHandler` + wire in `SecurityConfig`. Depends on `ErrorResponse` DTO only (already exists). Add `ObjectMapper` `@Bean` wiring in `AppConfig` or `SecurityConfig`.

3. **Finding 15** — Add `.requestMatchers(POST, "/api/documents/*/comments").authenticated()` to `SecurityConfig`. One-line change, group with step 2.

4. **Findings 1, 2, 9, 6** — `DocumentController` cleanup: `ServletUriComponentsBuilder` for Location headers, remove dead `@ResponseStatus(CREATED)`, `ResponseEntity.ok()` on update, `ResponseEntity.noContent()` on delete. All in one file, zero business logic change.

5. **Findings 3, 17** — `CommentController.addComment` Location header + `CommentService.assertVisible` exception type change. These touch the same feature; bundle together. Tests for `CommentService` must be updated to expect `DocumentNotFoundException` instead of `AccessForbiddenException`.

6. **Findings 4, 11** — `ReadingListController.addItem` Location header. Verify `ReadingListItemResponse.documentId()` accessor. Bundle with step 5.

7. **Finding 12** — `OwnershipService` + service-layer existence checks. Follow the recommended approach: add existence guard at the top of `DocumentService.update`, `DocumentService.delete`, `ReadingListService.updateReadingList`, `ReadingListService.deleteReadingList`, `ReadingListService.getReadingList`. Update `OwnershipService` tests to ensure correct behavior.

8. **Finding 16** — `ReadingListService.addItem` visibility check. Depends on `Visibility` entity and `DocumentNotFoundException` already in scope — straightforward addition.

9. **Finding 8** — `AuthResponse` enhancement. Add `getExpirationSeconds()` to `JwtService`, update `AuthResponse` record, update `AuthService`, update `AuthService` tests.

10. **Findings 5, 10** — Pagination consolidation. Coordinate with frontend team on the breaking shape change. Update both controllers, add/update controller tests.

11. **Findings 18, 20** — `Accept-Ranges: none` header on `streamFile`; OpenAPI global response customizer.

12. **Finding 19** — Document the PUT/PATCH decision; no code change unless PATCH is added.

---

## 7. Acceptable As-Is

The following items require no code change:

- **`PUT /api/documents/{id}` returning 200:** RFC 7231 permits either 200 (with updated representation) or 204 (no body) for PUT. Returning 200 with the updated resource is a perfectly valid REST choice and is consistent across all four update endpoints in this codebase.

- **`POST /api/documents/*/interactions` returning 204:** The `logInteraction` endpoint records a side-effect (a view/interaction event) and creates no identifiable resource that a client would need to retrieve. 204 No Content is correct here; RFC 7231 does not require 201 for POST when no resource is created.

- **Finding 21 — Auto-login on register:** Returning an `AuthResponse` on `POST /api/auth/register` (201 with token) is an intentional design choice. No code change is needed, but the OpenAPI annotation for this endpoint should describe the response as a usable JWT token (handled by Finding 20's global response improvement).
