# Alexandria API Audit — Full Findings

---

## Finding 1 — Hardcoded relative URI in Location header for file upload

**Affected endpoint:** `POST /api/documents` (file upload)

**Inconsistency:** The Location header is built as `URI.create("/api/documents/" + detail.id())`, a hardcoded relative path string. This does not account for any reverse proxy prefix, context path, or scheme/host. `CategoryController.create()` correctly uses `ServletUriComponentsBuilder.fromCurrentRequest()` which reads the actual incoming request URI and appends the new resource ID.

**Correct behavior:** Use `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(detail.id()).toUri()` consistent with `CategoryController`.

**Severity:** Medium

---

## Finding 2 — `@ResponseStatus(CREATED)` and `ResponseEntity.created(...)` both present on `createArticle`

**Affected endpoint:** `POST /api/documents/article`

**Inconsistency:** The method carries `@ResponseStatus(CREATED)` at the annotation level AND returns `ResponseEntity.created(location).body(detail)`. In Spring MVC, a `ResponseEntity` return value overrides `@ResponseStatus` entirely. The `@ResponseStatus` annotation is therefore dead code. It misleads readers into thinking the status is set twice or that one takes precedence conditionally. The Location header IS set (it comes from `ResponseEntity.created(uri)`), but the `@ResponseStatus` is noise.

**Correct behavior:** Remove `@ResponseStatus(CREATED)` — the `ResponseEntity` already carries the correct status and Location. Alternatively, if no Location header is needed, return a plain body with `@ResponseStatus(CREATED)` and drop the `ResponseEntity`. Choose one mechanism consistently.

**Severity:** Low (no runtime defect, but code is misleading)

---

## Finding 3 — `addComment` returns 201 with no Location header

**Affected endpoint:** `POST /api/documents/{documentId}/comments`

**Inconsistency:** The method uses `@ResponseStatus(CREATED)` and returns a raw `CommentResponse`. There is no Location header pointing to the created comment. By contrast, `ReadingListController.createReadingList` returns 201 with a proper Location header. REST convention requires a `Location` header on every 201 response. The comment resource is addressable at `DELETE /api/documents/{documentId}/comments/{commentId}`, so a URI can be constructed.

**Correct behavior:** Return `ResponseEntity.created(location).body(response)` where location is built via `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(commentId).toUri()`. Remove `@ResponseStatus(CREATED)`.

**Severity:** Medium

---

## Finding 4 — `addItem` returns 201 with no Location header

**Affected endpoint:** `POST /api/reading-lists/{id}/items`

**Inconsistency:** Same pattern as Finding 3. The method uses `@ResponseStatus(CREATED)` and returns a raw `ReadingListItemResponse` without a Location header. `createReadingList` in the same controller does include a Location header, making this internally inconsistent within a single controller.

**Correct behavior:** Return `ResponseEntity.created(location).body(response)` with a Location pointing to the item, e.g. `/api/reading-lists/{id}/items/{documentId}`.

**Severity:** Medium

---

## Finding 5 — Two different pagination wrapper types across controllers

**Affected endpoints:**
- `GET /api/documents` → `PageResponse<DocumentSummary>` (custom wrapper)
- `GET /api/documents/{documentId}/comments` → `Page<CommentResponse>` (Spring `Page`)
- `GET /api/reading-lists` → `Page<ReadingListSummaryResponse>` (Spring `Page`)

**Inconsistency:** `DocumentController.list()` returns a custom `PageResponse<T>` type. The two other paginated endpoints return Spring's raw `Page<T>`, which serialises differently (includes Spring internal metadata fields like `pageable`, `sort`, `first`, `last`, `empty`). Clients consuming these endpoints must handle two structurally different JSON envelopes. This is a breaking contract inconsistency.

**Correct behavior:** Pick one pagination envelope and apply it everywhere. The custom `PageResponse<T>` (which likely exposes a stable, controlled shape) is preferable. Convert all paginated responses to that type. If Spring `Page<T>` is preferred, expose it consistently from all list endpoints.

**Severity:** High

---

## Finding 6 — `DocumentController.update()` returns raw `DocumentDetail` without `ResponseEntity`

**Affected endpoint:** `PUT /api/documents/{id}`

**Inconsistency:**
- `DocumentController.update()` returns `DocumentDetail` directly (implicit 200, no `ResponseEntity`)
- `UserController.updateUser()` returns `ResponseEntity.ok(UserSummary)`
- `CategoryController.update()` returns `ResponseEntity.ok(CategoryResponse)`
- `ReadingListController.updateReadingList()` returns `ResponseEntity.ok(ReadingListResponse)`

Three of the four update operations use `ResponseEntity.ok(...)`. One does not. The HTTP status code is identical (200), but the inconsistency in return style makes the codebase harder to reason about and harder to add response headers (e.g. ETag, Last-Modified) later.

**Correct behavior:** Return `ResponseEntity.ok(detail)` from `DocumentController.update()` for consistency.

**Severity:** Low

---

## Finding 7 — Authentication errors return no JSON body; all other errors return `ErrorResponse` JSON

**Affected flows:**
- Invalid/expired JWT → `JwtAuthenticationFilter` → `authenticationEntryPoint.commence()` → `HttpServletResponse.sendError(401)` → empty body or default container error page
- Unauthenticated request to protected endpoint → same path

**Inconsistency:** Every application-level error (404, 403, 400, 409, 500, etc.) is handled by `GlobalExceptionHandler` and returns a well-structured `ErrorResponse(status, error, message, timestamp, path)`. But JWT authentication failures bypass `GlobalExceptionHandler` entirely. Clients receive a 401 with an empty body (or a default HTML error page depending on the container), not a JSON `ErrorResponse`. This forces API clients to handle two completely different error formats.

**Correct behavior:** Implement a custom `AuthenticationEntryPoint` that writes a JSON `ErrorResponse` to the response output stream with `Content-Type: application/json`. Do the same for `AccessDeniedHandler` if Spring Security's default is also used.

**Severity:** High

---

## Finding 8 — `AuthResponse` is incomplete per OAuth2/JWT convention

**Affected endpoints:** `POST /api/auth/register`, `POST /api/auth/login`

**Inconsistency:** `AuthResponse` contains only a `token` field. Industry standard (RFC 6750, OAuth 2.0) and client expectations include:
- `token_type` (should be `"Bearer"`)
- `expires_in` (seconds until expiry — the server already configures expiration in JWT generation)
- Optionally `scope`

Without `token_type`, clients must hardcode the assumption that it is a Bearer token. Without `expires_in`, clients cannot proactively refresh or re-authenticate before the token expires.

**Correct behavior:** Add `token_type` (hardcoded `"Bearer"`) and `expires_in` (derived from the JWT expiration config) to `AuthResponse`.

**Severity:** Medium

---

## Finding 9 — DELETE endpoints consistency

**All DELETE endpoints:**
- `DELETE /api/documents/{id}` → `@ResponseStatus(NO_CONTENT)` ✓
- `DELETE /api/categories/{id}` → `ResponseEntity.noContent().build()` ✓
- `DELETE /api/reading-lists/{id}` → `ResponseEntity.noContent().build()` ✓
- `DELETE /api/reading-lists/{id}/items/{documentId}` → `ResponseEntity.noContent().build()` ✓
- `DELETE /api/documents/{documentId}/comments/{commentId}` → `ResponseEntity.noContent().build()` ✓

**Finding:** All DELETEs correctly return 204 No Content. However, `DocumentController.delete()` uses `@ResponseStatus(NO_CONTENT)` while all others use `ResponseEntity.noContent().build()`. This is a minor style inconsistency — same HTTP semantics, different mechanism.

**Correct behavior:** Standardise on `ResponseEntity.noContent().build()` to match the rest of the codebase.

**Severity:** Low

---

## Finding 10 — Pagination inconsistency (same as Finding 5, additional angle)

**Affected endpoints:** `GET /api/documents/{documentId}/comments` vs `GET /api/documents`

**Inconsistency (detailed):** `CommentController.getComments()` returns `Page<CommentResponse>`. Spring's `Page<T>` when serialised to JSON produces a top-level `content` array plus internal fields: `pageable` (an object with `pageNumber`, `pageSize`, `sort`, `offset`, `paged`, `unpaged`), `totalPages`, `totalElements`, `last`, `first`, `size`, `number`, `sort`, `numberOfElements`, `empty`. The custom `PageResponse<T>` presumably exposes a cleaned-up subset. Clients consuming both endpoints receive structurally different pagination metadata. This is a contract defect.

**Correct behavior:** Same as Finding 5 — consolidate on one type. Centralise the mapping.

**Severity:** High (duplicate of Finding 5; listed separately per audit scope)

---

## Finding 11 — Inconsistent 201 responses within `ReadingListController`

**Affected endpoints:**
- `POST /api/reading-lists` → `ResponseEntity.created(location).body(response)` — 201 with Location ✓
- `POST /api/reading-lists/{id}/items` → `@ResponseStatus(CREATED)`, raw body — 201 without Location ✗

**Inconsistency:** Both are resource-creation operations in the same controller. One follows REST conventions; the other does not. The item resource is addressable (it can be deleted by `DELETE /api/reading-lists/{id}/items/{documentId}`), so a Location URI is constructable.

**Correct behavior:** Add Location header to `addItem`. Same fix as Finding 4.

**Severity:** Medium (same as Finding 4; listed separately per audit scope)

---

## Finding 12 — Ownership check conflates "not found" and "not owner" into 403

**Affected endpoints:** All endpoints protected by `@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")`, `@ownership.isReadingListOwner`, `@ownership.isCommentOwnerOrAdmin`

**Inconsistency:** `OwnershipService.isDocumentOwner()` returns `false` both when the document does not exist AND when it exists but is owned by a different user. Spring Security translates `false` from `@PreAuthorize` to a 403. This means:
- Request a document that doesn't exist → 403 (resource existence leaked by status code choice — attacker knows 403 means a document could exist at that ID)
- Actually, worse: a non-existent resource should be 404, not 403. 403 implies the resource exists but access is denied.

However, `DocumentService.get()` correctly returns 404 for private documents not owned by you (intentional masking). The ownership service takes the opposite approach for update/delete paths, always returning 403 regardless of existence. This is internally contradictory.

**Correct behavior:** `OwnershipService` should throw `NotFoundException` (not return `false`) when the resource does not exist, so Spring propagates a 404. When the resource exists but is not owned by the principal, return `false` (→ 403). This aligns with the pattern already used in `DocumentService.get()`.

**Severity:** High

---

## Finding 13 — `InvalidTokenException` not handled → 500

**Affected flow:** JWT validation failure inside `JwtAuthenticationFilter`

**Inconsistency:** `InvalidTokenException` is thrown by `JwtService` when a JWT is malformed or expired. It is NOT registered in `GlobalExceptionHandler`. If it somehow escapes the filter chain into the dispatcher servlet, it falls through to the generic `Exception` handler and returns 500. The correct status is 401. The filter currently calls `sendError(401)` before this can happen, but the missing handler is a latent defect — if the filter is refactored or the exception is thrown from a different context, it will produce 500.

**Correct behavior:** Add an `@ExceptionHandler(InvalidTokenException.class)` in `GlobalExceptionHandler` mapping to 401 with message "Invalid or expired token".

**Severity:** Medium

---

## Finding 14 — `UsernameNotFoundException` not handled → 500

**Affected flow:** `SecurityUtils.getCurrentUser()` is called from service layer. If the user authenticated with a valid JWT but has since been deleted from the database, `UsernameNotFoundException` is thrown.

**Inconsistency:** Not registered in `GlobalExceptionHandler`. Falls through to generic 500 handler. The correct response is 401 (the authenticated identity no longer exists).

**Correct behavior:** Add an `@ExceptionHandler(UsernameNotFoundException.class)` in `GlobalExceptionHandler` mapping to 401 with message "Authenticated user not found".

**Severity:** Medium

---

## Finding 15 — `addComment` auth enforcement is at service layer, not controller layer

**Affected endpoint:** `POST /api/documents/{documentId}/comments`

**Inconsistency:** The comment controller has no `@PreAuthorize` and the security config does not list this endpoint as `authenticated`. Auth is enforced inside the service via `securityUtils.getCurrentUser()`. This is invisible to security auditors reading the controller and security config. It also means unauthenticated calls reach the service, perform business logic, and only then fail — which is inefficient and inconsistent with every other auth-required endpoint.

**Correct behavior:** Add `.requestMatchers(HttpMethod.POST, "/api/documents/*/comments").authenticated()` to `SecurityConfig`, or add `@PreAuthorize("isAuthenticated()")` to the controller method.

**Severity:** Medium

---

## Finding 16 — `addItem` does not check document visibility

**Affected endpoint:** `POST /api/reading-lists/{id}/items`

**Inconsistency:** `ReadingListService.addItem()` does not verify that the document being bookmarked is visible to the requesting user. A user can add a `PRIVATE` document owned by another user to their reading list, thereby confirming that a private document with a specific UUID exists. This is an information disclosure vulnerability — it breaks the visibility masking that `DocumentService.get()` carefully maintains.

**Correct behavior:** Before adding the item, verify that the document is visible to the current user (public, or private and owned by the current user). If not, throw `DocumentNotFoundException` (same masking behavior as `DocumentService.get()`).

**Severity:** High

---

## Finding 17 — `CommentController.getComments` enforces visibility at service level but is marked `permitAll`

**Affected endpoint:** `GET /api/documents/{documentId}/comments`

**Inconsistency:** SecurityConfig marks this endpoint as `permitAll`. `CommentService` has an `assertVisible` check: if the document is PRIVATE, it throws `AccessForbiddenException` (→ 403). An unauthenticated user hitting a private document's comment endpoint gets 403. But `DocumentController.get()` returns 404 for the same private document when accessed without auth. The two endpoints handling the same document visibility produce different status codes (403 vs 404) for the same access condition.

**Correct behavior:** `CommentService.assertVisible` should use the same masking as `DocumentService.get()` — throw `DocumentNotFoundException` (→ 404) for private documents when the requester is not the owner, not `AccessForbiddenException` (→ 403).

**Severity:** Medium

---

## Finding 18 — `streamFile` returns 200, not 206 for range requests

**Affected endpoint:** `GET /api/documents/{id}/file`

**Inconsistency:** The endpoint ignores `Range` request headers and always returns 200 with the full file. For large files (PDFs, videos), clients (browsers, media players) commonly send `Range` headers expecting 206 Partial Content. Responding with 200 forces full re-download on resume and breaks video/audio seeking.

**Correct behavior:** Either (a) implement proper range request handling with `ResourceRegion` and 206 responses, or (b) explicitly document that range requests are not supported and return a `Accept-Ranges: none` header so clients know not to attempt it.

**Severity:** Medium

---

## Finding 19 — PATCH not in CORS allowed methods; no PATCH endpoints present

**Affected config:** `SecurityConfig` CORS configuration

**Finding:** CORS `allowedMethods` is `GET, POST, PUT, DELETE, OPTIONS`. PATCH is absent. No endpoints currently use PATCH. All partial updates use PUT (replacing the whole resource). This is currently consistent, but PUT semantics imply full resource replacement. If any field is optional in an update request (e.g., `UpdateUserRequest.displayName` is `@Size(max=255)` nullable, `password` is nullable — partial update semantics), PUT is being used with PATCH semantics.

`UpdateUserRequest` with nullable fields on a `PUT` endpoint is semantically incorrect — PUT means replace, so nullable fields imply the client can "remove" values by omitting them. This conflates PUT and PATCH.

**Correct behavior:** Either (a) require all fields on PUT and use null to mean "clear this field" explicitly, or (b) rename to PATCH and add PATCH to CORS allowed methods.

**Severity:** Low

---

## Finding 20 — OpenAPI config does not document 401/403 responses on authenticated endpoints

**Affected:** All authenticated endpoints; `OpenApiConfig.java`

**Inconsistency:** The source snapshot does not show any `@ApiResponse(responseCode = "401")` or `@ApiResponse(responseCode = "403")` annotations on controller methods. If `OpenApiConfig` only defines the security scheme without globally registering 401/403 as possible responses, the generated Swagger UI will show only the happy-path responses. API consumers will have no documentation of authentication/authorization failure shapes.

**Correct behavior:** Either (a) annotate each secured endpoint with `@ApiResponse(responseCode = "401", description = "Unauthenticated")` and `@ApiResponse(responseCode = "403", description = "Forbidden")`, or (b) register a global `OperationCustomizer` or use `@SecurityRequirement` globally in `OpenApiConfig` paired with global response codes via SpringDoc's `addResponseCode` configuration.

**Severity:** Low

---

## Finding 21 — `AuthResponse` returned immediately on `register` with no indication of auto-login

**Affected endpoint:** `POST /api/auth/register`

**Finding:** `register` returns an `AuthResponse` (JWT token), making registration implicitly auto-login. The response status is 201. This is an intentional design choice but has two contract concerns: (a) clients reading the 201 without inspecting the body might not realise they have received an auth token; (b) there is no documentation (OpenAPI) distinguishing that this 201 contains an auth token versus, say, a confirmation that registration succeeded and a separate login is required.

**Correct behavior:** No code change required if auto-login on register is intentional, but the OpenAPI documentation for this endpoint should explicitly state the response is a usable JWT token. Consider also adding `token_type` and `expires_in` (Finding 8 — same `AuthResponse`).

**Severity:** Info

---

## Summary Table

| # | Severity | Area | Short Description |
|---|----------|------|-------------------|
| 1 | Medium | Location header | File upload Location is hardcoded relative URI |
| 2 | Low | HTTP status | `@ResponseStatus` + `ResponseEntity` both on `createArticle` |
| 3 | Medium | Location header | `addComment` 201 missing Location header |
| 4 | Medium | Location header | `addItem` 201 missing Location header |
| 5 | High | Pagination | Two different pagination envelopes (`PageResponse` vs `Page`) |
| 6 | Low | Response style | `update()` returns raw type; all other updates use `ResponseEntity.ok()` |
| 7 | High | Error format | Auth errors return empty body; all others return JSON `ErrorResponse` |
| 8 | Medium | Auth contract | `AuthResponse` missing `token_type` and `expires_in` |
| 9 | Low | DELETE style | `delete()` uses `@ResponseStatus`; all others use `ResponseEntity.noContent()` |
| 10 | High | Pagination | Duplicate of Finding 5 — `Page<T>` vs `PageResponse<T>` in detail |
| 11 | Medium | Location header | `ReadingListController`: `createReadingList` has Location, `addItem` does not |
| 12 | High | Status codes | Ownership missing → 403 instead of 404; contradicts visibility masking |
| 13 | Medium | Exception handling | `InvalidTokenException` unhandled → falls to 500 |
| 14 | Medium | Exception handling | `UsernameNotFoundException` unhandled → falls to 500 |
| 15 | Medium | Auth enforcement | `addComment` auth enforced in service, invisible in controller/security config |
| 16 | High | Information disclosure | `addItem` does not check document visibility — private doc UUID enumerable |
| 17 | Medium | Status codes | Private doc comments return 403; direct doc access returns 404 |
| 18 | Medium | HTTP semantics | `streamFile` always 200; ignores Range headers |
| 19 | Low | HTTP semantics | PUT used with partial-update (PATCH) semantics in `UpdateUserRequest` |
| 20 | Low | OpenAPI docs | 401/403 responses not documented on authenticated endpoints |
| 21 | Info | OpenAPI docs | Auto-login on register not documented in OpenAPI |
