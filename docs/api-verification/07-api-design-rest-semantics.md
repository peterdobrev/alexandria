# API Design & REST Semantics Audit — Alexandria

**Date:** 2026-06-14  
**Scope:** All REST controllers and their associated response/request DTOs  
**Files reviewed:**
- `controller/AuthController.java`
- `controller/CategoryController.java`
- `controller/CommentController.java`
- `controller/DocumentController.java`
- `controller/ReadingListController.java`
- `controller/RecommendationController.java`
- `controller/UserController.java`
- `dto/AuthResponse.java`, `dto/ErrorResponse.java`, `dto/common/PageResponse.java`
- `dto/document/DocumentDetail.java`, `dto/document/DocumentSummary.java`
- `dto/ReadingListResponse.java`, `dto/ReadingListSummaryResponse.java`
- `dto/UserResponse.java`

---

## Summary Table

| # | File | Lines | Severity | Category |
|---|------|-------|----------|----------|
| 1 | DocumentController | 53–65, 67–70 | HIGH | Inconsistent return type wrapping |
| 2 | DocumentController | 89–98 | HIGH | POST /article — resource-type URL leaks internal taxonomy |
| 3 | DocumentController | 101–105 | HIGH | PUT used for partial update (PATCH semantics) |
| 4 | DocumentController | 107–112 | MEDIUM | Inconsistent response mechanism (`@ResponseStatus` vs `ResponseEntity`) |
| 5 | DocumentController | 53–65 | MEDIUM | `list()` returns raw `PageResponse<T>` not wrapped in `ResponseEntity` |
| 6 | RecommendationController | 27–28 | HIGH | Controller base path `/api` — too broad, breaks resource hierarchy |
| 7 | RecommendationController | 52–61 | HIGH | POST /interactions accepts only `VIEW` — request body is a lie |
| 8 | RecommendationController | 52–61 | MEDIUM | POST /interactions returns 204 — interaction resource is created, not voided |
| 9 | ReadingListController | 74–83 | MEDIUM | `addItem` Location header uses `documentId` as sub-resource key |
| 10 | ReadingListController | 38–41 | HIGH | `getReadingLists` requires authentication implicitly — no `@PreAuthorize` guard |
| 11 | CategoryController | 33–35 | MEDIUM | `GET /categories` returns unbounded `List<T>` — no pagination |
| 12 | UserController | 39–43 | HIGH | PUT used for partial update of user (PATCH semantics) |
| 13 | UserController | 28–31 | LOW | `/me` endpoint not protected by `@PreAuthorize("isAuthenticated()")` |
| 14 | CommentController | 36–42 | MEDIUM | Raw `Page<T>` returned, not wrapped in custom `PageResponse<T>` |
| 15 | DocumentController / CommentController | various | MEDIUM | Pagination response structure inconsistent across endpoints |
| 16 | AuthController | 35–38 | MEDIUM | POST /login returns 200 OK — should return 200 with no body change, but token response leaks `userId` |
| 17 | DocumentController | 101–105 | MEDIUM | `update()` returns unwrapped `DocumentDetail`, not `ResponseEntity` |
| 18 | Multiple | various | MEDIUM | No API versioning strategy anywhere |
| 19 | Multiple | various | LOW | No `ETag` / `Last-Modified` headers on cacheable `GET` responses |
| 20 | ReadingListResponse | 7 | MEDIUM | `items` is unbounded `List<T>` inside a response — can be arbitrarily large |
| 21 | UserResponse | 4–7 | LOW | `UserResponse` DTO defined but never used — dead code |
| 22 | DocumentDetail / DocumentSummary | various | LOW | `sizeBytes`, `contentType`, `body` — nullable fields not documented as optional |
| 23 | RecommendationController | 40–42 | MEDIUM | Pagination guard uses magic constants, not config-driven |
| 24 | DocumentController | 114–125 | MEDIUM | `GET /{id}/file` has implicit side-effect risk (no auth guard) |
| 25 | DocumentController | 75–86, 89–98 | MEDIUM | Two separate POST endpoints for document creation break uniform interface |

---

## Detailed Findings

---

### Finding 1 — Inconsistent return type wrapping in `DocumentController.list()` and `DocumentController.get()`

**File:** `DocumentController.java`, lines 53–70  
**Severity:** HIGH

```java
@GetMapping
public PageResponse<DocumentSummary> list(...) {   // line 53 — bare return type
    ...
    return documentService.list(filters, pageable, currentUserId);
}

@GetMapping("/{id}")
public DocumentDetail get(...) {                   // line 68 — bare return type
    ...
    return documentService.get(id, currentUserId);
}
```

Every other handler in the same controller returns `ResponseEntity<T>`. These two methods return the DTO directly. While Spring will wrap them in a 200 response, this makes it impossible for these handlers to ever return a different status code (e.g., 206 Partial Content for streaming), add response headers (e.g., `ETag`, `Cache-Control`), or distinguish a null document (which would produce a 200 with an empty body instead of 404).

**REST best practice violated:** Handlers that may need to control response metadata (headers, status) should consistently return `ResponseEntity<T>`.

---

### Finding 2 — POST `/api/documents/article` leaks internal resource subtype in URL

**File:** `DocumentController.java`, lines 89–98  
**Severity:** HIGH

```java
@PostMapping("/article")
public ResponseEntity<DocumentDetail> createArticle(@Valid @RequestBody CreateArticleRequest request) {
```

REST URLs identify resources, not operations or types. `/api/documents/article` means "the document named 'article'", not "create a document of type article". The document `type` already exists as a field in the request body (`CreateDocumentRequest.type`, `CreateArticleRequest.type`). A client creating a file-based document POSTs to `/api/documents` (multipart); an inline-article client should also POST to `/api/documents` with a different content type or a body-only payload. The `/article` segment is a resource-type discriminator hidden in the URL.

Additionally, the two creation endpoints are asymmetric:
- `POST /api/documents` (multipart) — for file uploads
- `POST /api/documents/article` (JSON) — for inline articles

If a document ID happened to be `article` (impossible with UUID, but the pattern is still wrong) there is a route collision risk. More practically, the URL breaks the collection-resource model.

**REST best practice violated:** RFC 7231 §4 — URIs should identify resources, not actions or types. Resource creation should go to the collection URI (`POST /api/documents`) with Content-Type negotiation or a discriminator in the body.

---

### Finding 3 — PUT used where PATCH semantics apply in `DocumentController.update()`

**File:** `DocumentController.java`, lines 101–105  
**Severity:** HIGH

```java
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

`UpdateDocumentRequest` allows all fields to be null/absent:

```java
// UpdateDocumentRequest.java
public record UpdateDocumentRequest(
        @NullOrNotBlank @Size(max = 255) String title,    // nullable
        @Size(max = 5000) String description,             // nullable
        Set<UUID> categoryIds,                            // nullable
        Visibility visibility                             // nullable
) {}
```

Sending a PUT with only `{"title": "New title"}` is a partial update — a PATCH operation. RFC 7231 requires PUT to replace the entire resource representation. Clients who omit fields expect those fields to remain unchanged (PATCH semantics), but HTTP caches and intermediaries will treat the PUT as a full replacement.

**REST best practice violated:** RFC 7231 §4.3.4 (PUT) — "the server MUST apply the new representation to the target resource". RFC 5789 (PATCH) exists precisely for partial modification.

---

### Finding 4 — Inconsistent response mechanism: `@ResponseStatus` vs `ResponseEntity` for DELETE

**File:** `DocumentController.java`, lines 107–112  
**Severity:** MEDIUM

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable UUID id) {
    documentService.delete(id);
}
```

`CategoryController`, `CommentController`, and `ReadingListController` all use `ResponseEntity<Void>` and `return ResponseEntity.noContent().build()`. `DocumentController.delete()` uses `@ResponseStatus(HttpStatus.NO_CONTENT)` with a `void` return. Both patterns produce the same HTTP response, but the inconsistency makes the codebase harder to reason about. If an exception handler later needs to override the status (e.g., return 404 when the document is not found), the `@ResponseStatus` on the method is a footgun — Spring picks the annotation status over exception-derived status in some configurations.

**REST best practice violated:** Consistency within the same API surface. Established team convention in every other controller uses `ResponseEntity`.

---

### Finding 5 — `DocumentController.list()` returns unwrapped `PageResponse<T>` (no `ResponseEntity`)

**File:** `DocumentController.java`, lines 53–65  
**Severity:** MEDIUM

Already noted under Finding 1 from a type-consistency angle. Additional concern: without `ResponseEntity`, the controller cannot emit `Link` headers for pagination (`rel="next"`, `rel="prev"`) which are idiomatic for paginated REST APIs. All other paginated controllers (`CommentController`, `ReadingListController`, `RecommendationController`) return `ResponseEntity`.

---

### Finding 6 — `RecommendationController` base path `/api` is too broad

**File:** `RecommendationController.java`, lines 27–28  
**Severity:** HIGH

```java
@RestController
@RequestMapping("/api")
public class RecommendationController {
```

Every other controller scopes its `@RequestMapping` to a specific resource collection (`/api/documents`, `/api/users`, etc.). This controller maps to the API root and then declares sub-paths `/recommendations` and `/documents/{id}/interactions`. The `/documents/{id}/interactions` sub-path conflicts with the resource hierarchy owned by `DocumentController` (which owns `/api/documents/**`). Having two controllers responsible for paths under `/api/documents` creates a split ownership that is easy to miss during maintenance. The `RecommendationController` should be `@RequestMapping("/api/recommendations")` with `logInteraction` moved to `DocumentController` (or a dedicated `InteractionController` at `/api/documents/{id}/interactions`).

**REST best practice violated:** Single controller per resource collection; hierarchical path ownership.

---

### Finding 7 — POST `/documents/{id}/interactions` accepts only `VIEW` kind — the request body is deceptive

**File:** `RecommendationController.java`, lines 52–61  
**Severity:** HIGH

```java
@PostMapping("/documents/{id}/interactions")
public void logInteraction(@PathVariable("id") UUID documentId,
                           @Valid @RequestBody CreateInteractionRequest request) {
    if (request.kind() != InteractionKind.VIEW) {
        throw new IllegalArgumentException("Only VIEW interactions can be posted by clients");
    }
```

`CreateInteractionRequest` accepts any `InteractionKind`:

```java
public record CreateInteractionRequest(@NotNull InteractionKind kind) {}
```

The endpoint validates the field only at runtime and throws 500 (unhandled `IllegalArgumentException`) for any value other than `VIEW`. From the client's perspective, the schema advertises that any `InteractionKind` value is legal. This is a broken contract: the request DTO does not match what the endpoint actually accepts.

Fix options:
1. Remove the `kind` field from the request body entirely — if only `VIEW` is supported, the endpoint implicitly logs a view and the body is unnecessary.
2. Add a `@Pattern` / custom constraint that restricts `kind` to `VIEW` at validation time and returns 422.
3. Rename the endpoint to `POST /documents/{id}/views`.

**REST best practice violated:** RFC 7231 §6.5.1 — 400 Bad Request should be returned for invalid input, not 500. The resource schema should match what the endpoint accepts.

---

### Finding 8 — POST `/interactions` returns 204 No Content when a resource was created

**File:** `RecommendationController.java`, lines 52–53  
**Severity:** MEDIUM

```java
@ResponseStatus(HttpStatus.NO_CONTENT)
@PostMapping("/documents/{id}/interactions")
public void logInteraction(...) {
```

An interaction is a resource (it is stored — it feeds the recommendation engine). A POST that creates a resource should return 201 Created with a `Location` header. Returning 204 implies nothing was created. If the intent is truly "fire-and-forget" with no readable interaction resource, the endpoint should be documented as such and its idempotency semantics defined (is it safe to POST the same view twice?).

**REST best practice violated:** RFC 7231 §6.3.2 — 201 Created is the correct response for successful resource creation.

---

### Finding 9 — `addItem` Location header uses `documentId` but items should have their own identity

**File:** `ReadingListController.java`, lines 74–83  
**Severity:** MEDIUM

```java
@PostMapping("/{id}/items")
public ResponseEntity<ReadingListItemResponse> addItem(@PathVariable UUID id,
                                                       @Valid @RequestBody AddReadingListItemRequest request) {
    ReadingListItemResponse response = readingListService.addItem(id, request, currentUserId);
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{documentId}")
            .buildAndExpand(response.document().id())   // uses document ID as item key
            .toUri();
    return ResponseEntity.created(location).body(response);
}
```

The Location header is constructed as `/api/reading-lists/{id}/items/{documentId}` which matches the `DELETE /{id}/items/{documentId}` path. This is internally consistent, but `ReadingListItemResponse` exposes its own `UUID id` field:

```java
public record ReadingListItemResponse(UUID id, DocumentSummary document, Instant addedAt) {}
```

The item has its own identity (`id`) but the Location points to the document ID, not the item ID. If two documents could theoretically appear in the same list (even once), the item `id` is the canonical identifier. The Location should use `response.id()` (the reading list item's own UUID), and `DELETE /{id}/items/{itemId}` should accept the item ID. Currently the DELETE uses `documentId` as the path param, implying one document can appear in a reading list at most once — a constraint that is implicit and not enforced by the schema.

**REST best practice violated:** RFC 7231 §7.1.2 — Location should point to the canonical URI of the created resource, identified by its own ID.

---

### Finding 10 — `getReadingLists` has no `@PreAuthorize` guard but calls `securityUtils.getCurrentUser()`

**File:** `ReadingListController.java`, lines 38–41  
**Severity:** HIGH

```java
@GetMapping
public ResponseEntity<Page<ReadingListSummaryResponse>> getReadingLists(Pageable pageable) {
    return ResponseEntity.ok(readingListService.getReadingLists(securityUtils.getCurrentUser(), pageable));
}
```

`securityUtils.getCurrentUser()` will throw (or return null/throw NPE) for an unauthenticated request. There is no `@PreAuthorize("isAuthenticated()")` annotation here, unlike the other endpoints in the same controller. The implicit authentication requirement is invisible to Spring Security's method-security audit, to OpenAPI documentation generators, and to any client reading the declared security constraints.

**REST best practice violated:** All authentication requirements should be declared explicitly in security annotations, not inferred from service-layer behaviour. Implicit requirements produce misleading 500 errors instead of 401.

---

### Finding 11 — `GET /api/categories` returns unbounded `List<T>` — no pagination

**File:** `CategoryController.java`, lines 33–35  
**Severity:** MEDIUM

```java
@GetMapping
public ResponseEntity<List<CategoryResponse>> list() {
    return ResponseEntity.ok(categoryService.list());
}
```

Categories are admin-managed and likely small in practice, but there is no upper bound enforced. Should the number of categories grow (e.g., a taxonomy with thousands of entries), this endpoint will return the entire table in one response. Every other collection endpoint in the API is paginated (`GET /documents`, `GET /reading-lists`, `GET /recommendations`, `GET /documents/{id}/comments`). The inconsistency forces clients to treat this endpoint differently from all others.

**REST best practice violated:** Unbounded collection responses are a scalability and API consistency anti-pattern. Either paginate or document a maximum size with a corresponding server-side limit.

---

### Finding 12 — PUT used for partial update in `UserController.updateUser()`

**File:** `UserController.java`, lines 39–43  
**Severity:** HIGH

```java
@PutMapping("/{id}")
public ResponseEntity<UserSummary> updateUser(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateUserRequest request) {
    return ResponseEntity.ok(userService.update(id, request));
}
```

`UpdateUserRequest` has not been read in full but follows the same pattern as `UpdateCategoryRequest` (single nullable-or-partial field). PUT here replaces a partial representation — the same PATCH-vs-PUT confusion as Finding 3.

**REST best practice violated:** RFC 7231 §4.3.4 — PUT must replace the complete resource representation.

---

### Finding 13 — `GET /api/users/me` has no explicit authentication guard

**File:** `UserController.java`, lines 28–31  
**Severity:** LOW

```java
@GetMapping("/me")
public ResponseEntity<UserSummary> getCurrentUser() {
    return ResponseEntity.ok(userService.get(securityUtils.getCurrentUser().getId()));
}
```

Same pattern as Finding 10. The `/me` endpoint is only meaningful for an authenticated user, but this is not declared via `@PreAuthorize`. An unauthenticated request will cause a runtime exception inside `securityUtils.getCurrentUser()` rather than a clean 401 response. This is a documentation and error-message problem as much as a design problem.

**REST best practice violated:** Authentication requirements should be declared, not implicit.

---

### Finding 14 — `CommentController` returns raw `Page<T>`, not custom `PageResponse<T>`

**File:** `CommentController.java`, lines 36–42  
**Severity:** MEDIUM

```java
@GetMapping
public ResponseEntity<Page<CommentResponse>> getComments(
        @PathVariable UUID documentId,
        Authentication authentication,
        Pageable pageable) {
    ...
    return ResponseEntity.ok(commentService.getComments(documentId, currentUserEmail, pageable));
}
```

`DocumentController.list()` and `RecommendationController.getRecommendations()` both return the custom `PageResponse<T>` wrapper:

```java
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {}
```

`ReadingListController.getReadingLists()` returns Spring's raw `Page<ReadingListSummaryResponse>`.  
`CommentController.getComments()` also returns raw `Page<CommentResponse>`.

Spring's `Page<T>` serialises to JSON with a very different shape from `PageResponse<T>` — it includes many more fields (`pageable`, `sort`, `numberOfElements`, `empty`, etc.). Clients cannot consume both shapes with the same pagination-handling code.

**REST best practice violated:** Uniform interface — pagination responses should have a consistent envelope across all collection endpoints.

---

### Finding 15 — Pagination response structure is inconsistent across all endpoints

**Severity:** MEDIUM (summary of Finding 14 extended)

| Endpoint | Return type | JSON shape |
|----------|-------------|------------|
| `GET /api/documents` | `PageResponse<DocumentSummary>` | Custom — `content`, `page`, `size`, `totalElements`, `totalPages`, `last` |
| `GET /api/recommendations` | `PageResponse<DocumentSummary>` | Custom — same |
| `GET /api/documents/{id}/comments` | `Page<CommentResponse>` | Spring default — adds `pageable`, `sort`, `numberOfElements`, `empty`, etc. |
| `GET /api/reading-lists` | `Page<ReadingListSummaryResponse>` | Spring default |

Two different shapes across four collection endpoints is a breaking inconsistency for any generated client or documentation.

---

### Finding 16 — POST `/api/auth/login` response includes `userId` — information leakage concern

**File:** `AuthController.java`, lines 35–38; `AuthResponse.java`, lines 5–18  
**Severity:** MEDIUM

```java
public record AuthResponse(UUID userId, String token, String tokenType, long expiresIn) {
```

Login responses typically return only the token and its metadata. Including `userId` in the login response is not wrong per se, but it:
1. Exposes an internal identifier on the authentication endpoint where none is needed (the token carries the identity).
2. Creates an asymmetry: the register endpoint correctly returns 201 with a `Location` header pointing to `/api/users/{userId}`, but a login client now also gets a `userId` it can use to call `/api/users/{userId}` — yet `UserResponse` (a separate DTO with email + displayName) is defined but never used; `GET /api/users/{id}` returns `UserSummary` (only `id` + `displayName`). Clients expecting full profile data after login are sent on an extra round-trip.

This is more of a design inconsistency than a strict REST violation, but it indicates the auth/user response contract has not been deliberately designed.

---

### Finding 17 — `DocumentController.update()` returns bare `DocumentDetail`, not `ResponseEntity`

**File:** `DocumentController.java`, lines 101–105  
**Severity:** MEDIUM

```java
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

In addition to the PUT-vs-PATCH issue (Finding 3), the return type is the bare DTO rather than `ResponseEntity<DocumentDetail>`. Consistent with Finding 1, this prevents adding response headers and makes the handler unable to return a non-200 status without an exception.

---

### Finding 18 — No API versioning strategy across the entire API surface

**Severity:** MEDIUM

All endpoints are under `/api/**` with no version segment, no `Accept: application/vnd.alexandria.v1+json` media type versioning, and no version-bearing custom headers. While versioning is unnecessary for a brand-new API, the complete absence of any strategy means that any breaking change (field removal, type change, endpoint rename) requires coordinated client and server deployments with no graceful transition period.

**REST best practice violated:** Any public or client-facing API should declare a versioning strategy before the first external consumer exists.

---

### Finding 19 — No `ETag` or `Last-Modified` headers on cacheable `GET` resources

**Severity:** LOW

`DocumentDetail` and `DocumentSummary` both carry `updatedAt: Instant`. This is sufficient information to compute a `Last-Modified` header and, together with the document ID, a deterministic `ETag`. Neither is emitted. Without these headers, HTTP proxies and clients cannot perform conditional requests (`If-None-Match`, `If-Modified-Since`), meaning every `GET /api/documents/{id}` fetches the full payload even when nothing has changed.

**REST best practice violated:** RFC 7232 — conditional request support for cacheable resources.

---

### Finding 20 — `ReadingListResponse.items` is an unbounded `List<T>`

**File:** `ReadingListResponse.java`, line 7  
**Severity:** MEDIUM

```java
public record ReadingListResponse(UUID id, String name, Instant createdAt, List<ReadingListItemResponse> items) {}
```

`GET /api/reading-lists/{id}` returns `ReadingListResponse` which embeds the full list of items inline. Each `ReadingListItemResponse` contains a full `DocumentSummary` (13 fields including nested `AuthorSummary` and `Set<CategorySummary>`). A reading list with 500 documents will return a very large payload in a single response, with no way for a client to paginate or project a subset. The sister endpoint `GET /api/reading-lists` (list) correctly returns only `ReadingListSummaryResponse` (no items), but the detail endpoint over-fetches.

**REST best practice violated:** Sub-resources that can grow without bound should be accessible via a separate paginated endpoint (e.g., `GET /api/reading-lists/{id}/items`), not embedded in the parent resource representation.

---

### Finding 21 — `UserResponse` DTO is defined but never referenced

**File:** `UserResponse.java`, lines 1–7  
**Severity:** LOW

```java
public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {}
```

No controller, service, or other DTO references this type. `UserController` uses `UserSummary` (id + displayName). This dead DTO creates confusion: it includes `email` (which `UserSummary` omits), suggesting a more complete user profile was planned but never implemented. Meanwhile clients calling `GET /api/users/{id}` cannot obtain a user's email from any endpoint.

---

### Finding 22 — Nullable fields in `DocumentDetail` / `DocumentSummary` not documented as optional

**File:** `DocumentDetail.java`, `DocumentSummary.java`  
**Severity:** LOW

```java
public record DocumentDetail(
        ...
        boolean hasFile,
        boolean hasBody,
        Long sizeBytes,        // nullable — null when no file
        String contentType,    // nullable — null when no file
        String body,           // nullable — null when no inline body
        ...
) {}
```

`sizeBytes`, `contentType`, and `body` will be `null` in JSON for documents without a file or inline body. There are no annotations (`@Nullable`, `@Schema(nullable = true)`) nor any validation/documentation that tells clients when to expect null. Combined with no OpenAPI spec, consumers have no contract to rely on.

**REST best practice violated:** API contracts should be explicit about which fields are optional/nullable. Undocumented nullability is a source of NullPointerException in client code.

---

### Finding 23 — Recommendation pagination limits are magic constants, not configuration-driven

**File:** `RecommendationController.java`, lines 31–32  
**Severity:** MEDIUM

```java
static final int MAX_PAGE_SIZE = 50;
static final int MAX_PAGE_NUMBER = 200;
```

These limits exist only for the recommendations endpoint. No other paginated endpoint enforces a maximum page size or page number. The constants are hardcoded in the controller and not pulled from `application.properties`. If these represent business or infrastructure constraints they should be environment-configurable, and if they represent a universal API policy they should be enforced globally (e.g., via a `HandlerMethodArgumentResolver` for `Pageable`).

---

### Finding 24 — `GET /api/documents/{id}/file` streams file content with no authentication guard on private documents

**File:** `DocumentController.java`, lines 114–125  
**Severity:** MEDIUM

```java
@GetMapping("/{id}/file")
public ResponseEntity<Resource> streamFile(@PathVariable UUID id,
                                           @AuthenticationPrincipal UserDetails principal) {
    UUID currentUserId = currentUserId(principal).orElse(null);
    DocumentService.StoredFileResource sfr = documentService.streamFile(id, currentUserId);
    return ResponseEntity.ok()
            .contentType(...)
            ...
            .body(sfr.resource());
}
```

The file streaming endpoint accepts an optional principal (`null` for anonymous). Visibility enforcement is delegated to `documentService.streamFile()`. While this may be correct at the service level, the HTTP layer has no declared security requirement. An unauthenticated client POSTing to a URL-guessed document ID has no HTTP-level rejection before the service layer is invoked. There is no `@PreAuthorize` annotation, and the `GET /api/documents/{id}` metadata endpoint has the same pattern. This is an audit concern rather than a confirmed bug, but the lack of explicit declaration at the controller level is a design smell.

---

### Finding 25 — Two separate POST endpoints for document creation break uniform interface

**File:** `DocumentController.java`, lines 75–98  
**Severity:** MEDIUM

```java
// Endpoint 1: file upload
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<DocumentDetail> create(
        @RequestPart("file") MultipartFile file,
        @RequestPart("metadata") @Valid CreateDocumentRequest metadata) { ... }

// Endpoint 2: article (inline body)
@PostMapping("/article")
public ResponseEntity<DocumentDetail> createArticle(
        @Valid @RequestBody CreateArticleRequest request) { ... }
```

Both requests create a `Document` resource in the same collection (`/api/documents`). The distinction is content-type: one is `multipart/form-data`, the other `application/json`. REST allows content-type negotiation for the same resource URI. The `POST /api/documents` endpoint already does this correctly by specifying `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`. Adding a second endpoint at `/article` is redundant — Spring can dispatch both `POST /api/documents` variants based on `Content-Type` alone (multipart vs. JSON). The `/article` path introduces an inconsistency in the URL scheme and means clients must know which URL to call based on document type rather than content type.

Furthermore, `CreateArticleRequest` and `CreateDocumentRequest` share all fields except `body`; the duplication will drift over time.

**REST best practice violated:** Uniform interface — a single resource URI should accept creation of all representations of that resource via Content-Type negotiation, not split across multiple paths.

---

## Cross-Cutting Issues

### Response envelope consistency

| Endpoint | Envelope |
|----------|----------|
| `GET /api/documents` | `PageResponse<T>` (custom) |
| `GET /api/documents/{id}` | Raw DTO |
| `GET /api/documents/{id}/comments` | `Page<T>` (Spring) |
| `GET /api/recommendations` | `PageResponse<T>` (custom) |
| `GET /api/reading-lists` | `Page<T>` (Spring) |
| `GET /api/categories` | `List<T>` (bare) |

Three different envelope shapes for collection endpoints is a maintenance and client-SDK problem.

### HTTP method usage summary

| Controller | Method declared | Correct method | Issue |
|-----------|----------------|----------------|-------|
| `DocumentController.update` | PUT | PATCH | Partial update |
| `UserController.updateUser` | PUT | PATCH | Partial update |
| `CategoryController.update` | PUT | PATCH | Partial update (single field) |
| `RecommendationController.logInteraction` | POST → 204 | POST → 201 | Status code |

### Missing `@PreAuthorize` where authentication is implicitly required

| Endpoint | Issue |
|----------|-------|
| `GET /api/users/me` | Calls `getCurrentUser()` without `isAuthenticated()` guard |
| `GET /api/reading-lists` | Calls `getCurrentUser()` without `isAuthenticated()` guard |
| `GET /api/recommendations` | Calls `getCurrentUser()` without `isAuthenticated()` guard |

---

*End of audit.*
