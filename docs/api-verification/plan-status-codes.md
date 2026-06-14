Now I have a complete picture of the codebase. Here is the implementation plan:

---

# Alexandria HTTP Status Code Findings — Implementation Plan

## 1. Executive Summary

The audit identified 21 findings. After analysis against the live source code, the breakdown is:

**Genuine bugs / RFC violations requiring fixes (10 findings):**
- Finding 13 — `UsernameNotFoundException` from service layer returns 500 (Critical)
- Finding 16 — Private document accessible via `addItem()` with no 404 masking (Critical)
- Finding 10 — Unauthenticated callers on `@PreAuthorize` endpoints receive 403 instead of 401 (High)
- Finding 15 — `addComment()` has no `@PreAuthorize`; auth enforcement is fragile (High)
- Finding 17 — `CommentService.assertVisible()` returns 403 for private docs, breaking the 404-masking strategy (High)
- Finding 3 — `Location` header is a relative URI in `DocumentController.create()` and `createArticle()` (Medium)
- Finding 7 — `addComment()` returns 201 with no `Location` header (Medium)
- Finding 8 — `addItem()` returns 201 with no `Location` header (Medium)
- Findings 11/12 — 401 responses carry no structured JSON body (Medium, cross-cutting)
- Findings 1/2 — Dead `@ResponseStatus(CREATED)` annotations alongside `ResponseEntity` (Low)

**Acceptable design choices / no changes required (8 findings):**
Findings 4, 5, 6, 9, 14, 18, 19, 20, 21 — see Section 6.

---

## 2. Prioritised Fix List

| # | Finding | Endpoint | Current Code | Required Change | Severity | Effort |
|---|---------|----------|-------------|-----------------|----------|--------|
| A | 13 | Any endpoint calling `SecurityUtils.getCurrentUser()` | `UsernameNotFoundException` falls through to generic 500 handler | Add `@ExceptionHandler(UsernameNotFoundException.class)` → 401 in `GlobalExceptionHandler` | Critical | XS |
| B | 16 | `POST /api/reading-lists/{id}/items` | `addItem()` fetches doc without visibility check | Check doc visibility against current user before adding item | Critical | S |
| C | 10+11+12 | All `@PreAuthorize` endpoints; any endpoint with no token or bad token | 403 for unauthenticated; empty-body 401s | Custom `AuthenticationEntryPoint` + fix `AccessDeniedException` handler to check authentication state | High | M |
| D | 15 | `POST /api/documents/{documentId}/comments` | No `@PreAuthorize` on `addComment()` | Add `@PreAuthorize("isAuthenticated()")` | High | XS |
| E | 17 | `GET /api/documents/{id}/comments` on private doc | `AccessForbiddenException` (403) for PRIVATE doc | Change `CommentService.assertVisible()` to throw `DocumentNotFoundException` | High | XS |
| F | 3 | `POST /api/documents`, `POST /api/documents/article` | `URI.create("/api/documents/...")` — relative URI | Use `ServletUriComponentsBuilder.fromCurrentRequest()` | Medium | XS |
| G | 7 | `POST /api/documents/{documentId}/comments` | `@ResponseStatus(CREATED)`, no `Location` header | Return `ResponseEntity.created(location).body(...)` | Medium | XS |
| H | 8 | `POST /api/reading-lists/{id}/items` | `@ResponseStatus(CREATED)`, no `Location` header | Return `ResponseEntity.created(location).body(...)` | Medium | XS |
| I | 1+2 | `POST /api/documents`, `POST /api/documents/article` | Dead `@ResponseStatus(CREATED)` annotations | Remove both annotations | Low | XS |

---

## 3. Implementation Steps

### Fix A — Handle `UsernameNotFoundException` as 401 in `GlobalExceptionHandler`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/exception/GlobalExceptionHandler.java`

Add after the `handleBadCredentials` handler (after line 195):

```java
// Before: no handler for UsernameNotFoundException — falls through to handleGeneric → 500
// After: explicit 401 with consistent ErrorResponse body

@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                             HttpServletRequest request) {
    log.warn("Authentication required on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

Also add to imports: `org.springframework.security.core.userdetails.UsernameNotFoundException`

**Test strategy:** Unit test `GlobalExceptionHandlerTest` — invoke the handler directly with a `UsernameNotFoundException` instance; assert the response is 401 with `message = "Authentication required"`. Integration test: call `POST /api/documents` with a valid but revoked JWT (user deleted from DB between issuance and request); assert HTTP 401 with JSON body.

---

### Fix B — Visibility check in `ReadingListService.addItem()`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/ReadingListService.java`

The `addItem()` method signature must receive the current user's ID. This requires a change in the controller as well.

**ReadingListService.java — change `addItem` signature and add check (lines 75-89):**

```java
// Before
public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request) {
    ...
    Document document = documentRepository.findById(request.documentId())
            .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
    // NO visibility check

// After
public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request, UUID currentUserId) {
    ...
    Document document = documentRepository.findById(request.documentId())
            .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
    if (document.getVisibility() == Visibility.PRIVATE
            && !document.getAuthor().getId().equals(currentUserId)) {
        throw new DocumentNotFoundException(request.documentId());
    }
```

The import `com.alexandria.entity.Visibility` is already used transitively — check if present, add if not.

**ReadingListController.java — pass current user to addItem (line 80):**

```java
// Before
public ReadingListItemResponse addItem(@PathVariable UUID id,
                                       @Valid @RequestBody AddReadingListItemRequest request) {
    return readingListService.addItem(id, request);

// After
public ReadingListItemResponse addItem(@PathVariable UUID id,
                                       @Valid @RequestBody AddReadingListItemRequest request) {
    UUID currentUserId = securityUtils.getCurrentUser().getId();
    return readingListService.addItem(id, request, currentUserId);
```

**Test strategy:** `ReadingListServiceTest` — given a PRIVATE document owned by user A, when user B calls `addItem`, then `DocumentNotFoundException` is thrown (not a visibility exception and not a null). Also test that user A can add their own PRIVATE document. Integration test: POST item with private document UUID owned by another user; assert 404.

---

### Fix C — Cross-cutting 401/403 correctness (Findings 10, 11, 12)

This is covered in full in Section 4 below. The changes touch three locations:
- New class: `JsonAuthenticationEntryPoint` in `com.alexandria.security`
- `JwtAuthenticationFilter.java` — replace `response.setStatus(401); return;` with entry point delegation
- `GlobalExceptionHandler.java` — update `handleAccessDenied` to differentiate authenticated vs anonymous

**GlobalExceptionHandler.java — `handleAccessDenied` (lines 154-159):**

```java
// Before
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                        HttpServletRequest request) {
    log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.FORBIDDEN, "Access denied", request);
}

// After
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                        HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean isAuthenticated = auth != null && auth.isAuthenticated()
            && !(auth instanceof AnonymousAuthenticationToken);
    if (!isAuthenticated) {
        log.warn("Unauthenticated access attempt on {}", request.getRequestURI());
        return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }
    log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.FORBIDDEN, "Access denied", request);
}
```

Add imports: `org.springframework.security.core.Authentication`, `org.springframework.security.core.context.SecurityContextHolder`, `org.springframework.security.authentication.AnonymousAuthenticationToken`

**Test strategy:** `GlobalExceptionHandlerTest` — two cases: (1) `SecurityContext` has no authentication → assert 401; (2) `SecurityContext` has a valid authenticated principal → assert 403.

---

### Fix D — Add `@PreAuthorize("isAuthenticated()")` to `addComment()`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/CommentController.java`

```java
// Before (line 44-46)
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(

// After
@PreAuthorize("isAuthenticated()")
@PostMapping
public ResponseEntity<CommentResponse> addComment(
```

(The `@ResponseStatus` annotation will be removed as part of Fix G below, which adds the `ResponseEntity` wrapper with a `Location` header.)

**Test strategy:** `CommentControllerTest` (or integration test) — assert that `POST /api/documents/{id}/comments` with no token returns 401, not 500 or 403.

---

### Fix E — `CommentService.assertVisible()` must throw `DocumentNotFoundException` for private docs

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/service/CommentService.java`

```java
// Before (lines 62-70)
private void assertVisible(Document document, String currentUserEmail) {
    if (document.getVisibility() == Visibility.PUBLIC) { return; }
    if (currentUserEmail != null && currentUserEmail.equals(document.getAuthor().getEmail())) { return; }
    throw new AccessForbiddenException("Access denied");
}

// After
private void assertVisible(Document document, String currentUserEmail) {
    if (document.getVisibility() == Visibility.PUBLIC) { return; }
    if (currentUserEmail != null && currentUserEmail.equals(document.getAuthor().getEmail())) { return; }
    throw new DocumentNotFoundException(document.getId());
}
```

**Test strategy:** `CommentServiceTest` — given a PRIVATE document and a non-owner email, when `getComments` is called, then `DocumentNotFoundException` is thrown (not `AccessForbiddenException`). Integration test: `GET /api/documents/{privateId}/comments` as a different user → assert 404, not 403.

---

### Fix F — Absolute `Location` URI in `DocumentController`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/DocumentController.java`

Add import `org.springframework.web.servlet.support.ServletUriComponentsBuilder` (already present in `ReadingListController`; not yet in `DocumentController`).

```java
// Before (lines 79-82)
return ResponseEntity
        .created(URI.create("/api/documents/" + detail.id()))
        .body(detail);

// After (same pattern for both create() and createArticle())
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(detail.id())
        .toUri();
return ResponseEntity.created(location).body(detail);
```

Apply the same replacement at line 89-92 for `createArticle()`.

The `java.net.URI` import becomes unused after this change and can be removed.

**Test strategy:** `DocumentControllerTest` (MockMvc) — assert the `Location` response header starts with `http://` (absolute). Can use `MockMvcRequestBuilders.post(...)` and assert `header().string("Location", startsWith("http"))`.

---

### Fix G — Add `Location` header to `CommentController.addComment()`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/CommentController.java`

```java
// Before (lines 44-50)
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    return commentService.addComment(documentId, request, securityUtils.getCurrentUser());
}

// After
@PreAuthorize("isAuthenticated()")
@PostMapping
public ResponseEntity<CommentResponse> addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
    CommentResponse response = commentService.addComment(documentId, request, securityUtils.getCurrentUser());
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{commentId}")
            .buildAndExpand(response.id())
            .toUri();
    return ResponseEntity.created(location).body(response);
}
```

Add imports: `java.net.URI`, `org.springframework.web.servlet.support.ServletUriComponentsBuilder`, `org.springframework.http.ResponseEntity`. Remove `org.springframework.http.HttpStatus` (no longer needed after removing `@ResponseStatus`).

Verify `CommentResponse` has an `id()` accessor — check the DTO; if not, that must be added first.

**Test strategy:** Assert `Location` header is present and points to `/api/documents/{documentId}/comments/{commentId}`.

---

### Fix H — Add `Location` header to `ReadingListController.addItem()`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/ReadingListController.java`

```java
// Before (lines 75-81)
@PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
@ResponseStatus(HttpStatus.CREATED)
@PostMapping("/{id}/items")
public ReadingListItemResponse addItem(@PathVariable UUID id,
                                       @Valid @RequestBody AddReadingListItemRequest request) {
    return readingListService.addItem(id, request);
}

// After
@PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
@PostMapping("/{id}/items")
public ResponseEntity<ReadingListItemResponse> addItem(@PathVariable UUID id,
                                                        @Valid @RequestBody AddReadingListItemRequest request) {
    UUID currentUserId = securityUtils.getCurrentUser().getId();
    ReadingListItemResponse response = readingListService.addItem(id, request, currentUserId);
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{documentId}")
            .buildAndExpand(response.documentId())
            .toUri();
    return ResponseEntity.created(location).body(response);
}
```

Note: this also incorporates the `currentUserId` passthrough from Fix B in the same edit. Remove `org.springframework.http.HttpStatus` import if no longer needed. Verify `ReadingListItemResponse` exposes `documentId()`.

**Test strategy:** Assert `Location` header is present and ends with the document UUID.

---

### Fix I — Remove dead `@ResponseStatus(CREATED)` annotations

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/controller/DocumentController.java`

Remove `@ResponseStatus(HttpStatus.CREATED)` at line 74 (before `create()`) and line 86 (before `createArticle()`). The `HttpStatus` import can remain as it is used by... check — if no other method uses it, remove it too. After Fix F, no `@ResponseStatus` will remain in this file; remove both `import org.springframework.http.HttpStatus` and `import org.springframework.web.bind.annotation.ResponseStatus`.

**Test strategy:** No functional test needed; verify compilation passes and existing 201 tests still pass.

---

## 4. Cross-Cutting Concern: Structured JSON for All 401 Responses

Three findings (10, 11, 12) all result in 401 responses without a structured `ErrorResponse` body. The root cause is that the JWT filter and Spring Security's `ExceptionTranslationFilter` both bypass `GlobalExceptionHandler` — one writes directly to the response, the other fires a `sendError()` at the servlet level.

**Single fix: a custom `AuthenticationEntryPoint` that writes `ErrorResponse` JSON.**

### Step 1 — Create `JsonAuthenticationEntryPoint`

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
        objectMapper.writeValue(response.getWriter(), body);
    }
}
```

### Step 2 — Register it in `SecurityConfig`

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/security/SecurityConfig.java`

```java
// Before (lines 60-62, bean declaration)
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                               JwtAuthenticationFilter jwtAuthenticationFilter,
                                               CorsConfigurationSource corsConfigurationSource) {

// After — add ObjectMapper parameter and wire the entry point
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                               JwtAuthenticationFilter jwtAuthenticationFilter,
                                               CorsConfigurationSource corsConfigurationSource,
                                               ObjectMapper objectMapper) {
    JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(objectMapper);
    return http
        ...
        .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
        .build();
}
```

Remove the lambda `(_, res, _) -> res.sendError(...)` at the current line 80.

### Step 3 — Fix `JwtAuthenticationFilter` to delegate to the entry point for invalid/expired tokens

**File:** `/Users/I765724/Documents/alexandria/alexandria/src/main/java/com/alexandria/security/JwtAuthenticationFilter.java`

Inject the entry point into the filter so it can use it for the invalid-token case:

```java
// Before (lines 46-50)
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}

// After
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
    entryPoint.commence(request, response, new BadCredentialsException(e.getMessage(), e));
    return;
}
```

This requires `JwtAuthenticationFilter` to hold a reference to `JsonAuthenticationEntryPoint`. Update the constructor to accept it:

```java
// The filter class gains one new field
private final JsonAuthenticationEntryPoint entryPoint;
```

Update `SecurityConfig.jwtAuthenticationFilter()` bean method to pass the entry point. Since both the filter and the `SecurityFilterChain` bean need the entry point, extract it as a `@Bean`:

```java
@Bean
public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return new JsonAuthenticationEntryPoint(objectMapper);
}

@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                        UserDetailsService userDetailsService,
                                                        JsonAuthenticationEntryPoint entryPoint) {
    return new JwtAuthenticationFilter(jwtService, userDetailsService, entryPoint);
}
```

**Test strategy:**
- Unit test `JsonAuthenticationEntryPointTest` — mock `HttpServletResponse`, invoke `commence()`, assert `Content-Type: application/json`, status 401, and body is a valid `ErrorResponse` with `status=401`.
- Integration test: call any protected endpoint with no token → assert 401 JSON body. Call with an expired token → assert 401 JSON body. Both should have `{"status":401,"error":"Unauthorized",...}`.

---

## 5. Recommended Implementation Order

Implement in this sequence to avoid introducing new broken states:

1. **Fix A** (GlobalExceptionHandler — `UsernameNotFoundException` → 401): smallest change, eliminates the Critical 500 bug immediately and unblocks integration testing of other fixes.

2. **Fix C — Step 1+2** (create `JsonAuthenticationEntryPoint`, wire into `SecurityConfig`): replaces the inline lambda. No filter changes yet. Confirms the no-token case now returns structured JSON.

3. **Fix C — Step 3** (update `JwtAuthenticationFilter` to delegate to the entry point): fixes the empty-body 401 for expired/invalid tokens.

4. **Fix C — GlobalExceptionHandler `handleAccessDenied`** (differentiate 401 vs 403 based on authentication state): fixes Finding 10.

5. **Fix D** (`@PreAuthorize("isAuthenticated()")` on `addComment()`): one-liner, closes the fragile auth gap.

6. **Fix E** (`CommentService.assertVisible()` → throw `DocumentNotFoundException`): closes the 404-masking leak. Note: this changes the existing 403 behaviour; update existing tests that assert 403.

7. **Fix B** (visibility check in `ReadingListService.addItem()`): Critical security fix. Requires signature change propagated through the controller. Do this before Fix H to avoid a partially broken state.

8. **Fix F** (absolute Location URI in `DocumentController`): straightforward refactor.

9. **Fix G** (Location header for `addComment()`): combines with Fix D changes in the same method.

10. **Fix H** (Location header for `addItem()`): incorporates the `currentUserId` passthrough from Fix B in the same edit.

11. **Fix I** (remove dead `@ResponseStatus` annotations): cosmetic last step, easy rollback if needed.

---

## 6. Acceptable As-Is

The following findings represent valid design choices and require no code changes:

| Finding | Endpoint | Current Behaviour | Why It Is Acceptable |
|---------|----------|-------------------|----------------------|
| 4 | `PUT /api/documents/{id}` | 200 with updated `DocumentDetail` body | RFC 9110 explicitly permits 200 for PUT when the server returns the new resource state. Useful when the server normalises data. |
| 5 | `PUT /api/users/{id}` | 200 with `UserSummary` body | Same reasoning. Deliberately excludes password from the response — the body serves as confirmation of what was persisted. |
| 6 | `PUT /api/categories/{id}` | 200 with updated `CategoryResponse` body | Consistent with Findings 4 and 5. Internal API coherence is maintained. |
| 9 | `POST /api/documents/{id}/interactions` | 204 No Content | Correct for a fire-and-forget telemetry/analytics POST. The client has nothing to do with a response body. |
| 14 | `InvalidTokenException` unhandled in `GlobalExceptionHandler` | Would produce 500 if reached | No live code path currently reaches the handler with this exception type. The filter always catches it first. Acceptable to leave until a second call site for `JwtService` is added. |
| 18 | `PUT`/`DELETE` on non-existent resources | 403 via `OwnershipService.orElse(false)` | Defensible as enumeration protection: callers cannot use PUT/DELETE to confirm resource existence. Should be documented as an intentional design decision in a code comment on `OwnershipService`. |
| 19 | `GET /api/documents/{id}/file` | Always 200, no `Accept-Ranges` | Range request support (206) is a meaningful enhancement but not a correctness bug. Acceptable as a future improvement when media streaming requirements are confirmed. |
| 20 | `POST /api/auth/login` and `/register` | `{"token": "..."}` | The API is not OAuth2. Renaming the field to `access_token` and adding `token_type`/`expires_in` is an improvement, but the current behaviour is unambiguous and consistent throughout the codebase. |
| 21 | `POST /api/documents` and `POST /api/documents/article` | No explicit `authenticated()` rule in `SecurityConfig` | Covered by `anyRequest().authenticated()`. Adding explicit rules would add noise without increasing security given that the `anyRequest()` fallback is the documented catch-all. Fix D and Fix A provide the defence-in-depth for the service layer. |
