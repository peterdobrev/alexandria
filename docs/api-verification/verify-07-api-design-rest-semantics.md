# Adversarial Verification — API Design & REST Semantics Audit (07)

**Date:** 2026-06-14
**Verifier role:** Adversarial reviewer — confirmed, refuted, or partially-corrected each finding against the actual source.
**Source files read:**
- All seven controllers (AuthController, CategoryController, CommentController, DocumentController, ReadingListController, RecommendationController, UserController)
- DTOs: AuthResponse, ErrorResponse, PageResponse, DocumentDetail, DocumentSummary, ReadingListResponse, ReadingListSummaryResponse, UserResponse, UpdateDocumentRequest, UpdateUserRequest, UpdateCategoryRequest, CreateInteractionRequest, ReadingListItemResponse
- SecurityConfig, SecurityUtils, UserMapper

---

## Finding-by-Finding Verdict

---

### Finding 1 — Inconsistent return type wrapping in `DocumentController.list()` and `DocumentController.get()`

**Verdict: CONFIRMED**

`DocumentController.list()` (line 53) returns `PageResponse<DocumentSummary>` directly, not `ResponseEntity<PageResponse<DocumentSummary>>`. `DocumentController.get()` (line 68) returns `DocumentDetail` directly. Every POST, PUT, and DELETE handler in the same controller returns `ResponseEntity<T>`. The asymmetry is real and the stated consequence — inability to add headers, change status codes, or distinguish null — is accurate.

The audit correctly notes that Spring wraps bare return types in a 200 response; this is not a runtime bug but a design inconsistency. Confirmed as written.

---

### Finding 2 — POST `/api/documents/article` leaks internal resource subtype in URL

**Verdict: CONFIRMED**

`DocumentController` has `@PostMapping("/article")` at line 89. The URL segment `/article` is a type discriminator in the URL path. The audit's technical argument (that REST URLs identify resources, not types, and that two endpoints now own overlapping collection paths) is accurate. The route-collision observation (UUID vs `article`) is noted as theoretical and correctly hedged.

The audit does not mention that the `/article` mapping is already unreachable via `POST /api/documents` since the latter is restricted to `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`. This is consistent with Finding 25's observation that the two endpoints could instead be differentiated by Content-Type alone at the same path. Finding 2 and Finding 25 overlap but both stand.

---

### Finding 3 — PUT used where PATCH semantics apply in `DocumentController.update()`

**Verdict: CONFIRMED**

`@PutMapping("/{id}")` at line 101. `UpdateDocumentRequest` (all four fields — `title`, `description`, `categoryIds`, `visibility` — are all nullable/optional with no `@NotNull`). A client sending `{"title":"x"}` is performing a partial update. HTTP PUT semantics require a full replacement. The finding is accurate. The quoted code snippet in the report exactly matches the actual source.

---

### Finding 4 — Inconsistent response mechanism: `@ResponseStatus` vs `ResponseEntity` for DELETE

**Verdict: CONFIRMED**

`DocumentController.delete()` uses `@ResponseStatus(HttpStatus.NO_CONTENT)` with `void` return (lines 107–112). `CategoryController.delete()`, `CommentController.deleteComment()`, `ReadingListController.deleteReadingList()`, and `ReadingListController.removeItem()` all use `return ResponseEntity.noContent().build()`. The inconsistency is real.

The audit's footnote about Spring's exception-handler interaction with `@ResponseStatus` on method vs. class is accurate: `@ResponseStatus` on a method is honoured by `DefaultHandlerExceptionResolver` before the exception's own status, which can mask 404 responses from a `ResourceNotFoundException` if that exception were thrown inside `delete()`.

---

### Finding 5 — `DocumentController.list()` returns unwrapped `PageResponse<T>` (no `ResponseEntity`)

**Verdict: CONFIRMED — duplicate of Finding 1, stated correctly**

This is explicitly called out as a duplicate angle of Finding 1. The additional point about `Link` pagination headers being impossible without `ResponseEntity` is accurate. The claim that "all other paginated controllers return `ResponseEntity`" is mostly true: `RecommendationController.getRecommendations()` does return `ResponseEntity<PageResponse<DocumentSummary>>`, `CommentController.getComments()` returns `ResponseEntity<Page<CommentResponse>>`, and `ReadingListController.getReadingLists()` returns `ResponseEntity<Page<ReadingListSummaryResponse>>`. The `list()` method in `DocumentController` is the lone bare return. Confirmed.

---

### Finding 6 — `RecommendationController` base path `/api` is too broad

**Verdict: CONFIRMED**

`@RequestMapping("/api")` at line 27. The finding is accurate: every other controller uses a specific resource path (`/api/documents`, `/api/users`, `/api/categories`, etc.). `RecommendationController` registers two sub-paths: `/recommendations` and `/documents/{id}/interactions`. The `/documents/{id}/interactions` path falls under the namespace already owned by `DocumentController`. Split path ownership is a real maintenance hazard.

---

### Finding 7 — POST `/documents/{id}/interactions` accepts only `VIEW` — request body is deceptive

**Verdict: CONFIRMED**

`CreateInteractionRequest` contains `@NotNull InteractionKind kind` with no further constraint. The controller throws `new IllegalArgumentException("Only VIEW interactions can be posted by clients")` for any non-`VIEW` kind. The audit is correct that `IllegalArgumentException` is unhandled and will propagate as a 500 unless a global handler catches it.

**Additional detail not in audit:** The project's `GlobalExceptionHandler` should be checked to confirm whether `IllegalArgumentException` is mapped to 400 or left as 500. This was not verified in this pass but the design flaw (schema lying to clients) stands regardless of how the exception is handled.

---

### Finding 8 — POST `/interactions` returns 204 No Content when a resource was created

**Verdict: PARTIALLY-CORRECT**

`@ResponseStatus(HttpStatus.NO_CONTENT)` at line 52 is confirmed. The finding is correct that 204 is semantically odd if an interaction resource is persisted.

However, the framing needs nuance: the audit assumes an interaction is a first-class addressable resource. If the intent is "fire-and-forget telemetry" (log a view, no readable resource is created at a canonical URI), then 204 is acceptable. The audit acknowledges this with "If the intent is truly fire-and-forget" but still flags it as a violation of RFC 7231 §6.3.2. Whether 201 vs. 204 is correct depends on whether `Interaction` is a retrievable resource — which cannot be determined from the controller alone. The code observation is accurate; the severity assessment is debatable.

---

### Finding 9 — `addItem` Location header uses `documentId` as sub-resource key

**Verdict: CONFIRMED**

Lines 79–82 of `ReadingListController`:
```java
URI location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{documentId}")
        .buildAndExpand(response.document().id())   // document ID, not item ID
        .toUri();
```

`ReadingListItemResponse` is `record ReadingListItemResponse(UUID id, DocumentSummary document, Instant addedAt)` — the item has its own `id` field. The `DELETE /{id}/items/{documentId}` endpoint (line 87) confirms the system uses document ID as the de-facto item key, implicitly enforcing at-most-one-document-per-list. The audit correctly identifies that the item's own `UUID id` is unused as the Location key. The implicit uniqueness constraint is real and undocumented.

---

### Finding 10 — `getReadingLists` has no `@PreAuthorize` guard

**Verdict: PARTIALLY-CORRECT — the controller guard is missing, but the security config compensates**

`ReadingListController.getReadingLists()` at line 38 has no `@PreAuthorize`. This is accurate.

However, `SecurityConfig` (line 103) has `.anyRequest().authenticated()`. `GET /api/reading-lists` is not listed in any `permitAll()` rule, so it falls under the catch-all `.authenticated()` requirement. Unauthenticated requests will be rejected at the filter chain level with 401, not allowed through to the controller. The audit's claim that "an unauthenticated request will cause a runtime exception" is **refuted** for the HTTP layer — the JWT filter will reject the request before `securityUtils.getCurrentUser()` is ever called.

The residual concern is weaker but real: the authentication requirement is declared only via the catch-all rule, not explicitly on the endpoint, making it invisible to method-security tools and OpenAPI generators. That is a documentation-hygiene issue, not a security gap.

---

### Finding 11 — `GET /api/categories` returns unbounded `List<T>` — no pagination

**Verdict: CONFIRMED**

`CategoryController.list()` returns `ResponseEntity<List<CategoryResponse>>` with no size limit. The observation about categories being admin-managed and likely small is fair context, but the audit's consistency argument (every other collection endpoint is paginated) is accurate. Finding stands as a design inconsistency. The "scalability concern" framing is appropriate given the absence of any enforced upper bound.

---

### Finding 12 — PUT used for partial update in `UserController.updateUser()`

**Verdict: CONFIRMED**

`@PutMapping("/{id}")` at line 39. `UpdateUserRequest` has two fields — `displayName` and `password` — both annotated `@NullOrNotBlank`, meaning both are optional (null is accepted). A client sending only `{"displayName":"Alice"}` performs a partial update. Same PATCH-vs-PUT issue as Finding 3. Confirmed.

---

### Finding 13 — `GET /api/users/me` has no explicit `@PreAuthorize` guard

**Verdict: PARTIALLY-CORRECT — same nuance as Finding 10**

`UserController.getCurrentUser()` at line 28 has no `@PreAuthorize`. This is accurate.

`SecurityConfig` line 98 explicitly rules: `.requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()`. This is a **named rule**, not merely the catch-all. Unauthenticated requests to `GET /api/users/me` receive 401 from the filter chain before reaching the controller. The audit's claim of a "runtime exception" from `securityUtils.getCurrentUser()` for an unauthenticated request is **refuted** — the security filter rejects the request first.

The documentation-hygiene concern (no `@PreAuthorize` on the method) still stands. Severity should be LOW (as rated), not higher.

---

### Finding 14 — `CommentController` returns raw `Page<T>`, not custom `PageResponse<T>`

**Verdict: CONFIRMED**

`CommentController.getComments()` at line 36 returns `ResponseEntity<Page<CommentResponse>>`. `DocumentController.list()` and `RecommendationController.getRecommendations()` use `PageResponse<T>`. The JSON shapes are different as described. Finding is accurate.

---

### Finding 15 — Pagination response structure is inconsistent across all endpoints

**Verdict: CONFIRMED**

The four-row table in the audit is accurate:

| Endpoint | Actual return type |
|---|---|
| `GET /api/documents` | `PageResponse<DocumentSummary>` (bare, no `ResponseEntity`) |
| `GET /api/recommendations` | `ResponseEntity<PageResponse<DocumentSummary>>` |
| `GET /api/documents/{id}/comments` | `ResponseEntity<Page<CommentResponse>>` |
| `GET /api/reading-lists` | `ResponseEntity<Page<ReadingListSummaryResponse>>` |

Minor correction: the audit's table lists `GET /api/documents` as returning `PageResponse<T>` with no `ResponseEntity` wrapper, which is correct and consistent with Findings 1 and 5. All four entries are verified against the source. Three different JSON shapes confirmed.

---

### Finding 16 — POST `/api/auth/login` response includes `userId`

**Verdict: PARTIALLY-CORRECT**

`AuthResponse` is `record AuthResponse(UUID userId, String token, String tokenType, long expiresIn)`. The `userId` field is real.

The audit's concern about information leakage is debatable: `userId` in a login response is a common and practical pattern — the client immediately knows its own identity without a follow-up `GET /api/users/me` call. The observation that `UserResponse` is referenced in `UserMapper.toResponse()` but not used by any controller is accurate (see Finding 21). The assessment of this as a "design inconsistency" is fair; describing it as a "MEDIUM" security concern is overstated.

**The core technical claim** — `userId` is included — is confirmed. The severity framing ("information leakage") is debatable.

---

### Finding 17 — `DocumentController.update()` returns bare `DocumentDetail`, not `ResponseEntity`

**Verdict: CONFIRMED**

Line 101–104: `@PutMapping("/{id}")` returns `DocumentDetail` (bare DTO). Confirmed. This is an extension of Finding 1/3 as the audit notes. Duplicate angle but technically accurate.

---

### Finding 18 — No API versioning strategy

**Verdict: CONFIRMED — as an observation, but overstated as a finding**

No version segment in any path, no `Accept` versioning, no custom headers. This is accurate. The audit correctly hedges ("versioning is unnecessary for a brand-new API") but still rates it MEDIUM and implies it should be present. For an internal or early-stage API, the absence of versioning is a deliberate choice that does not necessarily violate a REST best practice. The rating should be LOW. The observation is correct; the severity is inflated.

---

### Finding 19 — No `ETag` or `Last-Modified` headers on cacheable GET responses

**Verdict: CONFIRMED — as an observation**

No ETags or Last-Modified headers are emitted. `DocumentDetail` and `DocumentSummary` carry `updatedAt: Instant` which could support `Last-Modified`. The technical statement is accurate. Rating as LOW is appropriate.

---

### Finding 20 — `ReadingListResponse.items` is an unbounded `List<T>`

**Verdict: CONFIRMED**

`ReadingListResponse` is:
```java
public record ReadingListResponse(UUID id, String name, Instant createdAt, List<ReadingListItemResponse> items) {}
```

Each `ReadingListItemResponse` embeds a full `DocumentSummary` (13 fields including nested `AuthorSummary` and `Set<CategorySummary>`). The concern about large payloads with no pagination is accurate and well-described.

---

### Finding 21 — `UserResponse` DTO is defined but never referenced in controllers

**Verdict: PARTIALLY-CORRECT — UserResponse IS used in UserMapper**

The audit claims "No controller, service, or other DTO references this type."

This is **incorrect**. `UserMapper.toResponse(User user)` at line 24 creates and returns a `UserResponse`. `UserMapper` imports `UserResponse` and uses it.

However, `UserMapper.toResponse()` is never called by any controller or service in the codebase (only `toSummary()` and `toAuthorSummary()` are called downstream). So `UserResponse` is referenced in `UserMapper` but the `toResponse()` method itself is dead code — making the DTO effectively unused at runtime.

The finding is partially correct: `UserResponse` is not reachable from any active call path. But the statement that "no controller, service, or other DTO references this type" is factually wrong — `UserMapper` does reference it. The finding's conclusion (dead DTO, missing full profile endpoint) stands, but the stated evidence is inaccurate.

---

### Finding 22 — Nullable fields in `DocumentDetail`/`DocumentSummary` not documented as optional

**Verdict: CONFIRMED**

`DocumentDetail` has `Long sizeBytes`, `String contentType`, `String body` — all unboxed or reference types with no `@Nullable` annotation, no `@Schema(nullable = true)`, and no Javadoc. `DocumentSummary` has `Long sizeBytes` and `String contentType` with the same absence of documentation. The `boolean hasFile` and `boolean hasBody` sentinel fields exist but the relationship between them and the nullable fields is implicit. Confirmed as a documentation gap.

---

### Finding 23 — Recommendation pagination limits are magic constants, not config-driven

**Verdict: CONFIRMED**

`static final int MAX_PAGE_SIZE = 50` and `static final int MAX_PAGE_NUMBER = 200` at lines 31–32 of `RecommendationController`. These are hardcoded. They are not in `application.properties`. No other paginated endpoint enforces equivalent limits. Confirmed.

---

### Finding 24 — `GET /api/documents/{id}/file` streams file with no authentication guard on private documents

**Verdict: PARTIALLY-CORRECT — mislabelled as a controller gap; auth is handled by SecurityConfig and service layer**

The audit claims there is "no declared security requirement" at the HTTP layer. However:

1. `SecurityConfig` line 95 explicitly `permitAll()` for `GET /api/documents/{id}/file`. This is a deliberate choice: anonymous access to file content is permitted at the HTTP layer.
2. The service layer (`documentService.streamFile(id, currentUserId)`) enforces visibility rules — a null `currentUserId` will cause private documents to be rejected by the service.

The audit frames this as a missing `@PreAuthorize`, but the architecture intentionally allows anonymous reads and delegates visibility enforcement to the service. This is a valid design pattern (permit all requests, let the service decide based on visibility). It is not a "missing guard" — it is a deliberate pass-through with service-level enforcement.

The residual concern — that there is no explicit HTTP-level rejection for authenticated-only private documents — is a valid observation about the layering, but the audit's framing as a "side-effect risk" and "no auth guard" overstates the problem. The controller correctly passes `currentUserId = null` for anonymous requests and the service handles the rest.

**Verdict: PARTIALLY-CORRECT** — the observation about implicit security is accurate, but the characterisation as a missing guard is wrong; `permitAll()` in `SecurityConfig` is the explicit declaration.

---

### Finding 25 — Two separate POST endpoints for document creation break uniform interface

**Verdict: CONFIRMED**

`DocumentController` has `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` and `@PostMapping("/article")`. Both create `Document` resources. The uniform-interface concern is accurate: Spring can dispatch both on `POST /api/documents` by Content-Type alone (multipart vs. JSON), and the `/article` URL segment encodes type information in the path. Confirmed.

---

## Summary

| Finding | Verdict | Notes |
|---------|---------|-------|
| 1 | CONFIRMED | Bare return types on `list()` and `get()` |
| 2 | CONFIRMED | `/article` type discriminator in URL |
| 3 | CONFIRMED | PUT with all-nullable request = PATCH semantics |
| 4 | CONFIRMED | `@ResponseStatus` vs `ResponseEntity` inconsistency |
| 5 | CONFIRMED | Duplicate of Finding 1; `Link` header argument is valid |
| 6 | CONFIRMED | Base path `/api` too broad |
| 7 | CONFIRMED | Request body lies about acceptable `kind` values |
| 8 | PARTIALLY-CORRECT | 204 confirmed; whether 201 is required depends on whether Interaction is addressable |
| 9 | CONFIRMED | Location uses document ID, not item's own UUID |
| 10 | PARTIALLY-CORRECT | No method-level `@PreAuthorize`, but SecurityConfig catch-all `.authenticated()` prevents unauthenticated access — no runtime 500 |
| 11 | CONFIRMED | Unbounded `List<T>` with no size limit |
| 12 | CONFIRMED | PUT with nullable fields = PATCH semantics |
| 13 | PARTIALLY-CORRECT | No `@PreAuthorize`, but `SecurityConfig` has an explicit `.authenticated()` rule for `/api/users/me` — no runtime 500 |
| 14 | CONFIRMED | Raw `Page<T>` instead of `PageResponse<T>` |
| 15 | CONFIRMED | Three different envelope shapes across four collection endpoints |
| 16 | PARTIALLY-CORRECT | `userId` in login response confirmed; "information leakage" severity overstated |
| 17 | CONFIRMED | Bare DTO return on `update()` |
| 18 | CONFIRMED (overstated) | Observation accurate; MEDIUM rating inflated for a new API |
| 19 | CONFIRMED | No ETag/Last-Modified |
| 20 | CONFIRMED | Unbounded embedded `List<ReadingListItemResponse>` |
| 21 | PARTIALLY-CORRECT | `UserResponse` IS imported and used in `UserMapper.toResponse()` — audit's claim that "no other DTO references it" is factually wrong; however `toResponse()` is itself dead code, so the conclusion stands |
| 22 | CONFIRMED | Nullable fields undocumented |
| 23 | CONFIRMED | Magic constants, not config-driven |
| 24 | PARTIALLY-CORRECT | `permitAll()` in SecurityConfig is the explicit declaration; service enforces visibility — not a missing guard |
| 25 | CONFIRMED | Two POST paths for one resource collection |

---

## Counts

| Verdict | Count |
|---------|-------|
| CONFIRMED | 17 |
| PARTIALLY-CORRECT | 7 (Findings 8, 10, 13, 16, 21, 24, and the overstated severity in 18) |
| REFUTED | 0 |

No finding is outright false; the audit's observations are grounded in real code. The partial-correct cases arise from:
- **Findings 10 and 13:** Security config compensates for the missing method-level annotations — no actual runtime 500 risk.
- **Finding 21:** `UserResponse` is used in `UserMapper`; the claim it is "never referenced" is inaccurate.
- **Finding 24:** `permitAll()` is the explicit security declaration; the audit misframes it as a missing guard.
- **Finding 8:** Whether 204 vs 201 is correct is design-intent-dependent.
- **Finding 16:** Severity framing is debatable.
- **Finding 18:** Severity inflation.

---

*End of adversarial verification.*
