Now I have a complete picture of the codebase. Let me compile the exhaustive audit findings.

---

# Alexandria HTTP Status Code Audit

## Finding 1 — `@ResponseStatus` Conflict on `create()` (File Upload)

**Endpoint:** `POST /api/documents` (multipart)
**File:** `DocumentController.java:73-83`

**Current behavior:** The method carries both `@ResponseStatus(HttpStatus.CREATED)` (line 74) and returns `ResponseEntity.created(...).body(detail)` (lines 80-82). When a method returns a `ResponseEntity`, Spring MVC uses the status code embedded in the `ResponseEntity` and **ignores** the `@ResponseStatus` annotation entirely. The annotation is therefore dead code.

**Correct behavior:** The `@ResponseStatus(CREATED)` annotation is redundant but harmless — the actual wire response is 201 with a `Location` header, which is correct. The annotation should be removed to avoid misleading readers who might think it has effect.

**Severity:** Low (no runtime impact; purely misleading dead code)

---

## Finding 2 — `@ResponseStatus` Conflict on `createArticle()`

**Endpoint:** `POST /api/documents/article`
**File:** `DocumentController.java:85-93`

**Current behavior:** Identical to Finding 1. `@ResponseStatus(HttpStatus.CREATED)` at line 86 is dead because the method returns `ResponseEntity.created(...).body(detail)`. The `ResponseEntity` wins; the annotation is ignored.

**Correct behavior:** Same conclusion — the wire behavior is correct (201 + Location), but the annotation is misleading dead code and should be removed.

**Severity:** Low

---

## Finding 3 — Location URI Is Relative, Not Absolute

**Endpoint:** `POST /api/documents` and `POST /api/documents/article`
**File:** `DocumentController.java:81` and `DocumentController.java:91`

**Current behavior:**
```java
URI.create("/api/documents/" + detail.id())
```
This produces a **relative URI** (path-only, no scheme/host/port). RFC 9110 §10.2.2 states the `Location` header for a 201 Created response SHOULD be an absolute URI. `CategoryController.create()` (line 41-44) correctly uses `ServletUriComponentsBuilder.fromCurrentRequest()` to build an absolute URI dynamically.

**Correct behavior:** Use `ServletUriComponentsBuilder` to produce an absolute URI, as done in `CategoryController` and `ReadingListController.createReadingList()`. This matters in practice because some HTTP clients and intermediaries only accept absolute URIs in `Location` headers, and in deployments behind a reverse proxy the relative URI path alone is insufficient.

```java
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(detail.id())
        .toUri();
return ResponseEntity.created(location).body(detail);
```

**Severity:** Medium (RFC non-conformance; inconsistent with rest of API; breaks certain clients)

---

## Finding 4 — `DocumentController.update()` Returns 200 With Body (PUT)

**Endpoint:** `PUT /api/documents/{id}`
**File:** `DocumentController.java:95-100`

**Current behavior:** Returns `DocumentDetail` directly (implicit 200 via `@RestController`).

**Analysis:** Both 200 and 204 are legitimate for a PUT update. RFC 9110 explicitly allows 200 with a representation when the server wants to communicate the new state of the resource. 204 is preferred when the client already has the full state. For a document update where the server may normalise data (e.g., reconcile categories), returning 200 with the updated `DocumentDetail` is arguably superior because:
- The response body confirms what was actually persisted.
- Clients do not need a follow-up GET to refresh their view.

However, the method signature returns the raw `DocumentDetail` without a `ResponseEntity` wrapper, so there is no `Location` header or other contextual header. This is consistent with the read endpoint, so the design is internally coherent.

**Correct behavior:** 200 with body is defensible and correct here given the reconciliation logic. This is an acceptable design choice, not a bug.

**Severity:** Info (design choice; no violation)

---

## Finding 5 — `UserController.updateUser()` Returns 200 With Body (PUT)

**Endpoint:** `PUT /api/users/{id}`
**File:** `UserController.java:38-43`

**Current behavior:** `ResponseEntity.ok(userService.update(id, request))` — 200 with body.

**Analysis:** Same reasoning as Finding 4. A user profile update can meaningfully return the updated resource (especially since the server may normalise `displayName`). 200 with body is valid per RFC 9110. 204 would be more appropriate only if the client is guaranteed to already have all fields. Given that the endpoint performs a password update (where the response intentionally never echoes the password), returning the `UserSummary` (which excludes the password) is well-reasoned.

**Severity:** Info (design choice; no violation)

---

## Finding 6 — `CategoryController.update()` Returns 200 With Body (PUT)

**Endpoint:** `PUT /api/categories/{id}`
**File:** `CategoryController.java:48-53`

**Current behavior:** `ResponseEntity.ok(categoryService.update(id, request))` — 200 with body.

**Analysis:** Consistent with other update patterns in the codebase. No violation.

**Severity:** Info

---

## Finding 7 — `CommentController.addComment()` Has No `Location` Header

**Endpoint:** `POST /api/documents/{documentId}/comments`
**File:** `CommentController.java:44-50`

**Current behavior:** `@ResponseStatus(CREATED)` on a method returning raw `CommentResponse`. This correctly produces 201, but **no `Location` header** is set.

**Correct behavior:** RFC 9110 §10.2.2 says a 201 response SHOULD include a `Location` header identifying the newly created resource. Other 201 endpoints in this API (`CategoryController.create`, `ReadingListController.createReadingList`, both `DocumentController` create methods) all include a `Location` header. Comments have a natural URI: `/api/documents/{documentId}/comments/{commentId}`. The inconsistency undermines API discoverability.

Fix: return `ResponseEntity` with `Location` set, and drop `@ResponseStatus(CREATED)`.

**Severity:** Medium (inconsistency with rest of API; RFC SHOULD violation)

---

## Finding 8 — `ReadingListController.addItem()` Has No `Location` Header

**Endpoint:** `POST /api/reading-lists/{id}/items`
**File:** `ReadingListController.java:75-81`

**Current behavior:** `@ResponseStatus(CREATED)` on a method returning raw `ReadingListItemResponse`. No `Location` header.

**Correct behavior:** Same as Finding 7. The items sub-resource has a natural URI: `/api/reading-lists/{id}/items/{documentId}`. A `Location` header should be included per RFC 9110 §10.2.2. `createReadingList` in the same controller correctly sets `Location`.

**Severity:** Medium (same reasoning as Finding 7)

---

## Finding 9 — `RecommendationController.logInteraction()` Returns 204 for POST

**Endpoint:** `POST /api/documents/{id}/interactions`
**File:** `RecommendationController.java:52-61`

**Current behavior:** `@ResponseStatus(NO_CONTENT)` — 204 with no body.

**Analysis:** 204 No Content for a POST that records a fire-and-forget event is a valid and common pattern (used widely in analytics and telemetry APIs). The interaction is idempotent from the client's perspective (VIEW events are deduplicated in a 5-minute window). 202 Accepted would be another option if the logging were async, but since it commits synchronously, 204 is appropriate and correct.

**Severity:** Info (correct)

---

## Finding 10 — `@PreAuthorize` Failure Returns 403, Not 401 (Critical Distinction)

**Endpoints:** All endpoints with `@PreAuthorize` — `PUT /api/documents/{id}`, `DELETE /api/documents/{id}`, `PUT /api/users/{id}`, `PUT|DELETE /api/categories/{id}`, `DELETE /api/documents/{documentId}/comments/{commentId}`, `GET|PUT|DELETE /api/reading-lists/{id}`, `POST /api/reading-lists/{id}/items`, `DELETE /api/reading-lists/{id}/items/{documentId}`
**Files:** Multiple controllers; `GlobalExceptionHandler.java:154-159`

**Current behavior:** When `@PreAuthorize` evaluates to `false`, Spring Security throws `AccessDeniedException`. The `GlobalExceptionHandler` catches it and returns 403 Forbidden with body `{"message": "Access denied"}`.

**Analysis:** This is correct **only when the user is authenticated**. When an unauthenticated request hits a `@PreAuthorize`-protected endpoint:

1. The JWT filter runs, finds no token, and sets no authentication in the `SecurityContext`.
2. The `@PreAuthorize` SpEL expression (e.g., `@ownership.isDocumentOwner(#id, principal)`) is evaluated — `principal` is `null`.
3. All `OwnershipService` methods return `false` when `principal == null` (see lines 26, 36, 46, 57).
4. Spring Security throws `AccessDeniedException` because the method security interceptor sees an unauthenticated principal trying to access a secured method.
5. Spring Security's `ExceptionTranslationFilter` is supposed to convert `AccessDeniedException` for anonymous users to an authentication challenge (401), but this conversion happens **only if the exception travels through the filter chain without being caught first**.
6. **The `GlobalExceptionHandler` catches `AccessDeniedException` first** (line 154) and returns 403 unconditionally, bypassing Spring Security's `ExceptionTranslationFilter` logic.

**Expected behavior per RFC 9110:** 401 Unauthorized when no credentials are presented; 403 Forbidden when credentials are valid but insufficient. The current implementation returns 403 to unauthenticated callers on `@PreAuthorize` endpoints, which violates this semantic and hides whether the endpoint requires authentication at all.

**Severity:** High

---

## Finding 11 — JWT Absent on Authenticated Endpoint Returns 401 (Correct)

**Endpoints:** All endpoints with `anyRequest().authenticated()` in `SecurityConfig`
**File:** `SecurityConfig.java:77-80`

**Current behavior:** When no `Authorization` header is present, `JwtAuthenticationFilter.extractToken()` returns `null`, the filter skips authentication and calls `filterChain.doFilter()`. Spring Security's authorization layer then sees an anonymous principal on a protected endpoint and invokes the configured `authenticationEntryPoint`, which calls `response.sendError(SC_UNAUTHORIZED, "Unauthorized")`.

**Result:** 401 with empty body (no JSON `ErrorResponse` — raw Tomcat error page or empty response).

**Issue:** The 401 response from the `authenticationEntryPoint` bypasses `GlobalExceptionHandler` entirely (it fires at the servlet container level via `sendError`). The client therefore receives an inconsistent error format — no `ErrorResponse` JSON, just a Tomcat default. All other error responses from the API have a structured `ErrorResponse` body (status, error, message, timestamp, path).

**Severity:** Medium (protocol correctness is fine; format inconsistency with the rest of the API)

---

## Finding 12 — Malformed/Expired JWT Returns 401 With Empty Body

**Endpoints:** Any endpoint when a Bearer token is present but invalid
**File:** `JwtAuthenticationFilter.java:46-49`

**Current behavior:**
```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
    log.warn("JWT authentication failed on {}: {}", request.getRequestURI(), e.getMessage());
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}
```
The filter sets status 401 via `response.setStatus(...)` and returns without writing a body or calling `filterChain.doFilter()`. The response body is empty — the client receives HTTP/1.1 401 with zero bytes.

**Issues:**
1. Empty body is inconsistent with all other error responses (which have structured `ErrorResponse` JSON).
2. `response.setStatus()` does not commit the response in the same way as `sendError()` — the response is flushed with no body. This differs from how the `authenticationEntryPoint` handles the no-token case (`sendError` at least produces a Tomcat default page).
3. `UsernameNotFoundException` is caught here (when the JWT email is not in the database), which is correct for the filter. However, `UsernameNotFoundException` thrown **after** the filter (from `SecurityUtils.getCurrentUser()` in service or controller code) is NOT caught in the filter and falls through to `GlobalExceptionHandler`, which catches it as a generic `Exception` and returns 500. See Finding 13.

**Severity:** Medium (401 is correct; empty body is inconsistent)

---

## Finding 13 — `UsernameNotFoundException` From Service Layer Returns 500

**Endpoints:** `POST /api/documents/article`, `POST /api/documents` (multipart), `POST /api/documents/{documentId}/comments`, `POST /api/documents/{id}/interactions`, `GET /api/recommendations`, `GET /api/reading-lists`, `POST /api/reading-lists`
**File:** `SecurityUtils.java:14-22`; `GlobalExceptionHandler.java:197-201`

**Current behavior:** `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException` (a Spring exception not in the custom hierarchy) in two cases:
1. `authentication == null` in the `SecurityContext`.
2. The email from the JWT does not resolve to a user in the database.

`GlobalExceptionHandler` has no `@ExceptionHandler(UsernameNotFoundException.class)`. It falls through to the generic `Exception` handler and returns **500 Internal Server Error** with message "An unexpected error occurred".

**Expected behavior:**
- Case 1 (no authentication context): Should never occur at the service layer for endpoints protected by `anyRequest().authenticated()`, because the filter chain would have already rejected the request at 401. However, it CAN occur on `permitAll` endpoints that call `securityUtils.getCurrentUser()` directly (e.g., `CommentController.addComment()`, which has no `@PreAuthorize` and is not marked `authenticated()` in `SecurityConfig` — see Finding 15).
- Case 2 (user deleted between JWT issuance and request): This is a genuine edge case that should return 401 (credentials reference a non-existent identity), not 500.

**Fix:** Add an `@ExceptionHandler(UsernameNotFoundException.class)` in `GlobalExceptionHandler` returning 401.

**Severity:** Critical (legitimate user-facing scenario produces 500 instead of 401)

---

## Finding 14 — `InvalidTokenException` From Application Layer Returns 500

**File:** `GlobalExceptionHandler.java:197-201`; `JwtService.java:46-49`; `InvalidTokenException.java`

**Current behavior:** `InvalidTokenException` is only thrown by `JwtService.extractClaims()` and by the `JwtService` constructor (on weak key). Both are caught in `JwtAuthenticationFilter` before reaching the handler. However, `InvalidTokenException` is NOT handled in `GlobalExceptionHandler`. If it somehow escaped the filter (e.g., a future code path calls `JwtService` in a controller or service directly), it would produce 500.

**Analysis:** In the current codebase, `InvalidTokenException` is always caught in the filter. The gap is theoretical but represents a fragile design — the exception type carries clear semantic meaning (invalid token = 401) but has no handler. If the filter catch block is ever refactored to re-throw, the fallback is 500.

**Severity:** Low (no current path triggers this; future-proofing concern)

---

## Finding 15 — `CommentController.addComment()` Returns 500 for Unauthenticated Requests

**Endpoint:** `POST /api/documents/{documentId}/comments`
**File:** `CommentController.java:44-50`; `SecurityConfig.java:69-77`

**Current behavior trace:**
1. `SecurityConfig` has no explicit rule for `POST /api/documents/*/comments`. It falls under `anyRequest().authenticated()` (line 77).
2. An unauthenticated request (no token) therefore hits the `authenticationEntryPoint` first and gets 401. This part is correct.
3. However, `addComment()` has **no `@PreAuthorize` annotation**. The method relies entirely on `securityUtils.getCurrentUser()` at line 49 to enforce authentication at the service level.
4. If a request somehow reaches the controller with an anonymous principal that bypassed the filter chain (e.g., in tests, or if the security config is ever relaxed), `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException`, which returns 500 (Finding 13).

**The deeper issue:** The authentication enforcement for `addComment` is implicit and fragile:
- There is no `@PreAuthorize` on the controller method.
- Protection relies on `anyRequest().authenticated()` in the filter chain, which is not visible from the controller.
- If `SecurityConfig` is ever modified to add a `permitAll` rule for comments (as was done for `GET /api/documents/*/comments`), `addComment` would silently lose its auth protection and begin throwing 500 instead of 401 for unauthenticated users.

**Correct behavior:** Add `@PreAuthorize("isAuthenticated()")` or equivalent explicit annotation on `addComment()`.

**Severity:** High (fragile defence-in-depth; currently masked by `anyRequest()` but one config change away from a 500-producing hole)

---

## Finding 16 — Private Document Accessible via `ReadingListService.addItem()`

**Endpoint:** `POST /api/reading-lists/{id}/items`
**File:** `ReadingListService.java:75-90`

**Current behavior:** `addItem()` fetches the document by ID (line 78) and throws `DocumentNotFoundException` if absent, but performs **no visibility check**. An authenticated user can bookmark a PRIVATE document owned by another user by including its UUID in the request body.

**Expected behavior:** The service should check whether the document is visible to the requesting user before adding it to the reading list. The same visibility logic used in `DocumentService.get()` should be applied:
```java
if (document.getVisibility() == Visibility.PRIVATE
        && !document.getAuthor().getId().equals(currentUserId)) {
    throw new DocumentNotFoundException(document.getId());
}
```
Note: The `ReadingListController` passes only `id` (the list ID) to `addItem`; it does not pass the current user. The service receives `currentUser` indirectly from `ReadingListService.getReadingList` -> `list.getUser()` only for the bookmark log. The fix requires propagating `currentUser.getId()` into the visibility check.

**Impact:** An attacker who has guessed or enumerated a private document's UUID can confirm its existence (no 404 is thrown for accessible items) and pin it to a reading list, triggering a `BOOKMARK` interaction record against the document. This is an information disclosure and integrity violation.

**Severity:** Critical

---

## Finding 17 — 404 Masking of Private Documents Is Correct But Incomplete

**Endpoints:** `GET /api/documents/{id}`, `GET /api/documents/{id}/file`
**Files:** `DocumentService.java:136-143`, `DocumentService.java:172-184`

**Current behavior:** Both `get()` and `streamFile()` throw `DocumentNotFoundException` (→ 404) when the document is PRIVATE and the requester is not the owner. This intentionally prevents resource existence enumeration.

**Analysis of correctness:** This is a deliberate and documented design decision (comment in snapshot). The 404 approach is acceptable for an API where the existence of a private document is itself sensitive information. However:

1. `CommentController.getComments()` on a PRIVATE document returns **403** (`AccessForbiddenException`) rather than 404 (see `CommentService.assertVisible()`, line 62-70). This is **inconsistent**: a caller can distinguish "document doesn't exist" (404 from the document endpoint) from "document exists but is private" (403 from the comments endpoint) for any given UUID.

2. `InteractionService.assertVisible()` uses `DocumentNotFoundException` (→ 404), consistent with `DocumentService`.

**The inconsistency:** `CommentService.assertVisible()` throws `AccessForbiddenException` (→ 403) while `DocumentService.get()` throws `DocumentNotFoundException` (→ 404) for the identical visibility scenario. An attacker can confirm private document existence by querying `GET /api/documents/{id}/comments` and receiving 403 instead of 404.

**Severity:** High (security: breaks the 404-masking strategy by leaking document existence through the comments endpoint)

---

## Finding 18 — `OwnershipService` Returns `false` (→ 403) for Non-Existent Resources

**File:** `OwnershipService.java:24-33`, `45-54`

**Current behavior:** `isDocumentOwner()`, `isReadingListOwner()`, and `isSelf()` all return `false` when the resource is not found (`orElse(false)`). Spring's `@PreAuthorize` then throws `AccessDeniedException` → 403.

**Expected behavior:** If the document/list/user does not exist, the correct response is 404, not 403. Returning 403 for a non-existent resource reveals that the resource does not belong to the caller without confirming whether it exists at all — which is arguably acceptable in some designs, but is inconsistent with `DocumentService.get()` which returns 404 for non-existent documents and 404-masked for private ones.

**Concrete example:** `PUT /api/documents/{id}` with a random UUID:
- If the document exists and is not owned by the caller → 403 (correct)
- If the document does not exist → also 403 (wrong; should be 404)

A caller cannot distinguish "not your document" from "document does not exist" when using PUT/DELETE, but CAN distinguish them when using GET (which returns 404). This asymmetry is mildly confusing.

**Note:** For security purposes (preventing enumeration through update/delete), the 403-for-missing behaviour is actually a form of protection analogous to the 404-masking pattern. It is a defensible choice, but should be documented as intentional.

**Severity:** Medium (design inconsistency; defensible as enumeration protection, but undocumented)

---

## Finding 19 — `streamFile` Serves Complete File as 200, Not 206 for Range Requests

**Endpoint:** `GET /api/documents/{id}/file`
**File:** `DocumentController.java:109-120`

**Current behavior:** Always returns `ResponseEntity.ok()` (200) regardless of whether the client sent a `Range` header. No `Accept-Ranges` header is set.

**Expected behavior:** RFC 9110 §14 defines partial content. For file streaming endpoints (especially for large PDFs, videos, etc.), supporting `Range` requests (206 Partial Content) is expected by browser `<video>` and `<audio>` elements and by many download managers. Without `Accept-Ranges: bytes`, browsers cannot resume downloads or seek in media files.

**Severity:** Medium (functional limitation for media streaming; not a status code bug per se, but a meaningful omission for a document library)

---

## Finding 20 — `AuthResponse` Leaks Raw JWT With No Token Type or Expiry

**Endpoint:** `POST /api/auth/register`, `POST /api/auth/login`
**File:** `AuthResponse.java:3`

**Current behavior:**
```json
{ "token": "eyJhbGciOiJIUzI1NiJ9..." }
```
No `token_type`, no `expires_in`, no `issued_at`.

**Expected behavior per RFC 6750/OAuth2 convention:**
```json
{ "access_token": "...", "token_type": "Bearer", "expires_in": 86400 }
```
While this API is not OAuth2, the missing fields have practical consequences:
- Clients cannot self-validate token expiry without parsing the JWT.
- No `token_type` field means clients must infer the Bearer scheme.
- The field name `token` rather than `access_token` diverges from every major convention.

**Severity:** Low (not a status code issue; included because it is an API response correctness concern)

---

## Finding 21 — `SecurityConfig` Missing Explicit Rule for `POST /api/documents` and `POST /api/documents/article`

**File:** `SecurityConfig.java:67-77`

**Current behavior:** The `SecurityConfig` has no explicit `authenticated()` rule for `POST /api/documents` or `POST /api/documents/article`. These fall under `anyRequest().authenticated()`. This is functionally correct but means the security policy is not explicit in the config — a reader must understand the `anyRequest()` fallback to know these endpoints require auth.

**More importantly**, `POST /api/documents` (multipart) calls `securityUtils.getCurrentUser()` directly without `@PreAuthorize`, so if the `anyRequest()` rule were changed (or an integration test bypasses security), the endpoint would proceed to call `getCurrentUser()` and throw a 500 (Finding 13 again).

**Severity:** Low (same root cause as Finding 15; belt-and-suspenders concern)

---

## Summary Table

| # | Endpoint | Current Code | Current Status | Correct Status | Severity |
|---|----------|-------------|----------------|----------------|----------|
| 1 | POST /api/documents (multipart) | `@ResponseStatus(CREATED)` + `ResponseEntity.created()` | 201 (correct) | Dead annotation — remove | Low |
| 2 | POST /api/documents/article | Same conflict | 201 (correct) | Dead annotation — remove | Low |
| 3 | POST /api/documents, /article | `URI.create("/api/documents/...")` | Relative Location | Absolute URI via `ServletUriComponentsBuilder` | Medium |
| 4 | PUT /api/documents/{id} | Raw return, 200 implicit | 200 with body | 200 acceptable | Info |
| 5 | PUT /api/users/{id} | `ResponseEntity.ok(...)` | 200 | 200 acceptable | Info |
| 6 | PUT /api/categories/{id} | `ResponseEntity.ok(...)` | 200 | 200 acceptable | Info |
| 7 | POST /api/documents/{documentId}/comments | `@ResponseStatus(CREATED)`, no Location | 201, no Location | Add Location header | Medium |
| 8 | POST /api/reading-lists/{id}/items | `@ResponseStatus(CREATED)`, no Location | 201, no Location | Add Location header | Medium |
| 9 | POST /api/documents/{id}/interactions | `@ResponseStatus(NO_CONTENT)` | 204 | 204 correct | Info |
| 10 | All `@PreAuthorize` endpoints | `AccessDeniedException` → handler | 403 for unauthenticated | Should be 401 for unauthenticated callers | High |
| 11 | Authenticated endpoints, no token | `authenticationEntryPoint.sendError(401)` | 401, no JSON body | 401 correct; body format inconsistent | Medium |
| 12 | Any endpoint, invalid/expired token | Filter `response.setStatus(401)`, empty body | 401, empty body | 401 correct; body format inconsistent | Medium |
| 13 | Any endpoint calling `SecurityUtils.getCurrentUser()` | `UsernameNotFoundException` → generic handler | 500 | Should be 401 | Critical |
| 14 | N/A (no live path) | `InvalidTokenException` unhandled | 500 (if reached) | Should be 401 | Low |
| 15 | POST /api/documents/{documentId}/comments | No `@PreAuthorize`, relies on service | Currently 401 via filter | Fragile; add `@PreAuthorize("isAuthenticated()")` | High |
| 16 | POST /api/reading-lists/{id}/items | No visibility check on document | 200 for PRIVATE document owned by other | Should be 404 (same masking as GET) | Critical |
| 17 | GET /api/documents/{id}/comments | `AccessForbiddenException` for PRIVATE doc | 403 | Should be 404 (consistent with document endpoint) | High |
| 18 | PUT/DELETE on missing resources | `OwnershipService.orElse(false)` → 403 | 403 for non-existent resources | Arguable; document as intentional | Medium |
| 19 | GET /api/documents/{id}/file | `ResponseEntity.ok()` always | 200 | 206 for Range requests | Medium |
| 20 | POST /api/auth/register, /login | `AuthResponse(token)` | Response body field names | Follow `access_token`/`token_type` convention | Low |
| 21 | POST /api/documents, /article | No explicit security rule | Implicit via `anyRequest()` | Add explicit `authenticated()` rule | Low |
