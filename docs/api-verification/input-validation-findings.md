Now I have a complete picture of the codebase. Here is the full audit report:

---

# Alexandria API Input Validation Audit

**Date:** 2026-06-14
**Scope:** All REST endpoints in the Spring Boot backend
**Severity scale:** Critical / High / Medium / Low / Info

---

## Finding 1 — UpdateUserRequest: empty body accepted as a no-op

**Endpoint:** `PUT /api/users/{id}`
**Code location:** `dto/user/UpdateUserRequest.java` lines 5-12; `service/UserService.java` lines 31-41
**Severity:** Medium

Both `displayName` and `password` are optional with no `@NotNull` and no at-least-one-field constraint. `UserService.update()` treats null on each field as "no change", so `PUT /api/users/{id}` with body `{}` passes Bean Validation, reaches the service, touches no fields, saves the entity unchanged, and returns 200 with the current user summary.

This is a design choice, not a crash, but it is not appropriate because a PUT semantically replaces the resource. The two real options are:

1. Define a proper partial-update contract (PATCH), document it explicitly, and add a validator that requires at least one non-null field so that a completely empty request is rejected with 400.
2. Keep PUT but require at least one field to be supplied (add a class-level `@AssertTrue` or a custom constraint).

As written, clients receive silent success with no visible effect, which is misleading.

---

## Finding 2 — UpdateUserRequest.displayName: whitespace-only value accepted

**Endpoint:** `PUT /api/users/{id}`
**Code location:** `dto/user/UpdateUserRequest.java` line 7
**Severity:** Medium

`displayName` is annotated `@Size(max=255)` but has no blank check. Supplying `{"displayName": "   "}` passes validation and persists a whitespace-only display name into the database column (which is `varchar(255)` with no DB-level constraint). The existing `@NullOrNotBlank` custom annotation already exists in the codebase (`validation/NullOrNotBlank.java`) and is used on `UpdateDocumentRequest.title`; it should be applied here as well.

Contrast with `RegisterRequest.displayName` which has both `@NotBlank` and `@Size`, and with `UpdateDocumentRequest.title` which uses `@NullOrNotBlank`.

---

## Finding 3 — UpdateUserRequest.password: whitespace-only password accepted

**Endpoint:** `PUT /api/users/{id}`
**Code location:** `dto/user/UpdateUserRequest.java` line 10
**Severity:** High

`password` is annotated `@Size(min=8, max=255)`. A value of `"        "` (eight spaces) satisfies the size constraint and is accepted. BCrypt will happily hash it. The user's account is now "protected" by a password that is trivial to guess. `RegisterRequest.password` has `@NotBlank @Size(min=8)`, preventing the same problem at registration, but the update path has no blank check.

A `@NullOrNotBlank` (or a custom `@NullOrNotBlankAndSize`) constraint should be added, and ideally a pattern constraint preventing all-whitespace passwords. The minimum fix is to also add a blank rejection equivalent to what `RegisterRequest` enforces.

---

## Finding 4 — CreateReadingListRequest: name has no length upper bound

**Endpoint:** `POST /api/reading-lists`
**Code location:** `dto/CreateReadingListRequest.java` line 5
**Severity:** Medium

`@NotBlank` is the only constraint. A name of arbitrary length is accepted and will hit the database column `reading_lists.name varchar(255)` (schema `001-initial-schema.yaml` line 143), which will throw a JDBC `DataException` or Hibernate validation error at persistence time — surfaced as a 500 rather than a clean 400. Correct fix: add `@Size(max=255)`.

The same applies to `UpdateReadingListRequest` (same file pattern, same finding below).

---

## Finding 5 — UpdateReadingListRequest: name has no length upper bound

**Endpoint:** `PUT /api/reading-lists/{id}`
**Code location:** `dto/UpdateReadingListRequest.java` line 5
**Severity:** Medium

Identical to Finding 4. `@NotBlank` with no `@Size` max on a `varchar(255)` column. A 256+ character name will cause a DB-level error bubbled as 500.

---

## Finding 6 — CreateDocumentRequest.type / CreateArticleRequest.type: free-text string, not validated against a known set

**Endpoints:** `POST /api/documents` (multipart), `POST /api/documents/article`
**Code location:** `dto/document/CreateDocumentRequest.java` line 22; `dto/document/CreateArticleRequest.java` line 22; `repository/DocumentSpecifications.java` line 15; `service/DocumentService.java` line 153
**Severity:** Medium

`type` is `@NotBlank @Size(max=50)` in `CreateDocumentRequest` and only `@NotBlank` (no size limit) in `CreateArticleRequest`. There is no validation against a known enum or allowlist. Any arbitrary string is persisted and later used as a raw equality predicate in `DocumentSpecifications.hasType()`:

```java
return (root, query, cb) -> cb.equal(root.get("type"), type);
```

This means:
- The `type` column in the DB has no domain constraint, so garbage values proliferate.
- The `search` query parameter passed via `DocumentController.list()` as `type` hits the same `hasType()` spec — arbitrary strings are passed directly into JPA criteria with no sanitization (though parameterized queries prevent SQL injection, the values are still meaningless).
- `CreateArticleRequest.type` has no `@Size` upper bound at all; a very long string could hit the DB column `varchar(50)` and cause a 500.

The correct fix is to use the `Visibility` enum pattern: introduce a `DocumentType` enum, bind the field to it (Jackson will reject unknown values with a 400 via `HttpMessageNotReadableException`), and use `@NotNull` on the enum field.

---

## Finding 7 — DocumentController.list() `search` param: no length limit, potential performance issue

**Endpoint:** `GET /api/documents?search=...`
**Code location:** `controller/DocumentController.java` lines 53-63; `repository/DocumentSpecifications.java` lines 31-34; `service/DocumentService.java` line 162
**Severity:** Low

The `search` parameter has no `@Size` or length cap. It is passed into a LIKE query:

```java
String pattern = "%" + search.toLowerCase() + "%";
return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
```

A leading `%` wildcard forces a full index scan regardless. A multi-megabyte search string forces the DB to allocate that string as a query parameter on every call. There is no injection risk (it is a parameterized bind), but denial-of-service through repeated very-long search strings is a realistic concern. A reasonable cap (e.g. `@Size(max=200)`) on the parameter would prevent this. Note that `@RequestParam` parameters require `@Validated` on the controller class and a `@Size` on the parameter for Bean Validation to apply, or the check must be done manually.

---

## Finding 8 — Pageable page size unbounded in DocumentController, ReadingListController, and CommentController

**Endpoints:** `GET /api/documents`, `GET /api/reading-lists`, `GET /api/documents/{id}/comments`, `GET /api/reading-lists/{id}`, `GET /api/documents/*/comments`
**Code location:** `controller/DocumentController.java` line 57; `controller/ReadingListController.java` line 41; `controller/CommentController.java` line 38; `controller/RecommendationController.java` lines 40-47
**Severity:** High

`RecommendationController` explicitly caps page size at 50 and page number at 200 (lines 40-47). No other controller does this. A client can request `?size=100000` and Spring Data will pass it directly to the JPA query. In `DocumentController.list()` the query runs a Hibernate `findAll(spec, pageable)` against the full documents table — loading up to 100,000 rows with their category associations into a single HTTP response. Identical exposure exists in `ReadingListController.getReadingLists()` and `CommentController.getComments()`.

The fix is to apply the same MAX_PAGE_SIZE guard across all paged endpoints, either per-controller (as RecommendationController does) or globally via a `PageableHandlerMethodArgumentResolverCustomizer` bean that caps `maxPageSize`.

---

## Finding 9 — CreateArticleRequest.body: no length upper bound

**Endpoint:** `POST /api/documents/article`
**Code location:** `dto/document/CreateArticleRequest.java` line 23
**Severity:** Medium

The article `body` field is `@NotBlank` with no `@Size` max. The DB column `documents.body` is `columnDefinition = "text"` (entity line 59), which in PostgreSQL is unlimited. There is no multipart size limit on this path (unlike file upload which has `app.storage.max-file-size=50MB`). A client can POST a several-hundred-megabyte article body; the full string will be read into heap by Jackson, mapped to the entity, and written to the DB in a single transaction. This is a plausible denial-of-service vector. A reasonable cap (e.g. `@Size(max=500000)`) should be applied, and a `spring.servlet.multipart` or Tomcat `maxPostSize` equivalent should be set for JSON bodies.

---

## Finding 10 — CreateDocumentRequest.categoryIds / CreateArticleRequest.categoryIds: no size cap on the set

**Endpoints:** `POST /api/documents`, `POST /api/documents/article`
**Code location:** `dto/document/CreateDocumentRequest.java` line 24; `service/DocumentService.java` lines 187-197
**Severity:** Low

`categoryIds` is a `Set<UUID>` with no `@Size` constraint. `DocumentService.resolveCategories()` loops over every UUID and issues a `categoryRepository.findById()` per entry. A request with 10,000 category IDs would issue 10,000 individual SELECT queries in one transaction. A `@Size(max=20)` or similar constraint would close this N+1 amplification vector.

---

## Finding 11 — File upload: MIME type check trusts client-supplied Content-Type

**Endpoint:** `POST /api/documents` (multipart)
**Code location:** `storage/LocalFileStorageService.java` lines 41-44
**Severity:** High

```java
String contentType = file.getContentType();
if (contentType == null || !storageProperties.allowedContentTypes().contains(contentType)) {
    throw new InvalidDocumentContentException("Unsupported content type: " + contentType);
}
```

`file.getContentType()` returns the `Content-Type` declared in the multipart part header. This value is set entirely by the client. A malicious actor can upload an executable, HTML page, or SVG (JavaScript-capable) file with `Content-Type: application/pdf` and it will pass the allowlist check. The file is stored and later served back via `GET /api/documents/{id}/file` with the stored content type header:

```java
.contentType(MediaType.parseMediaType(sfr.contentType()))
```

This creates a stored XSS / content-sniffing vector: a browser that sniffs or trusts the `Content-Type: application/pdf` header and renders the actual HTML/SVG content inline would execute attacker-controlled markup. Server-side magic-byte inspection (Apache Tika or a lightweight byte-pattern check) is required to validate the actual file content, not just the declared type.

---

## Finding 12 — `GET /api/documents/{id}/file`: served inline without X-Content-Type-Options or Content-Security-Policy

**Endpoint:** `GET /api/documents/{id}/file`
**Code location:** `controller/DocumentController.java` lines 114-119
**Severity:** Medium

The file stream response uses `inline` disposition and sets the Content-Type to the value stored at upload time (which as shown above is client-controlled). No `X-Content-Type-Options: nosniff`, `Content-Security-Policy`, or `X-Frame-Options` headers are set on file responses. Even for legitimate PDFs, rendering inline in a browser frame without CSP allows clickjacking and PDF-based exploit delivery. At minimum, `X-Content-Type-Options: nosniff` should be added to file responses; for non-PDF types, `attachment` disposition is safer.

---

## Finding 13 — AddReadingListItemRequest: private documents can be bookmarked by non-owners

**Endpoint:** `POST /api/reading-lists/{id}/items`
**Code location:** `service/ReadingListService.java` lines 75-89`
**Severity:** Medium

`ReadingListService.addItem()` fetches the document and adds it to the list without checking its visibility:

```java
Document document = documentRepository.findById(request.documentId())
        .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
// No visibility/ownership check here
```

A user who knows or guesses the UUID of another user's `PRIVATE` document can add it to their reading list and thereby permanently register a `BOOKMARK` interaction for it (via `interactionService.logBookmark()`). The existence of the private document is also confirmed by the 201 success response (information disclosure). The fix is to apply the same visibility check used in `DocumentService.get()` and `InteractionService.assertVisible()`.

---

## Finding 14 — CommentController.addComment: no `@PreAuthorize`, auth enforced only at service layer

**Endpoint:** `POST /api/documents/{documentId}/comments`
**Code location:** `controller/CommentController.java` lines 44-49; `service/CommentService.java` line 41; `security/SecurityUtils.java` lines 14-22
**Severity:** Medium

The `addComment` method has no `@PreAuthorize` annotation. Authentication is enforced implicitly because `securityUtils.getCurrentUser()` throws `UsernameNotFoundException` when there is no authenticated principal. `UsernameNotFoundException` is NOT handled by `GlobalExceptionHandler` and falls through to the generic `Exception` handler, returning 500 instead of 401. The `anyRequest → authenticated` rule in `SecurityConfig` should block unauthenticated requests at the filter level for most paths, but the inconsistency between explicit `@PreAuthorize` on similar methods (e.g. `deleteComment`) and the implicit service-level guard is a maintenance hazard.

Additionally, `UsernameNotFoundException` bubbling as 500 is a separate bug (see Finding 16).

---

## Finding 15 — UsernameNotFoundException not handled: produces 500

**All authenticated endpoints that call SecurityUtils.getCurrentUser()**
**Code location:** `security/SecurityUtils.java` lines 21-22; `exception/GlobalExceptionHandler.java` lines 197-202
**Severity:** High

`SecurityUtils.getCurrentUser()` throws Spring Security's `UsernameNotFoundException` when the JWT is valid but the user has since been deleted from the database. `GlobalExceptionHandler` has no handler for `UsernameNotFoundException`. It falls through to the generic `Exception` handler and returns 500 with `"An unexpected error occurred"` — leaking an internal error for what is a legitimate client-facing condition (the account no longer exists). The correct response is 401. A `@ExceptionHandler(UsernameNotFoundException.class)` returning 401 should be added.

---

## Finding 16 — InvalidTokenException not handled: produces 500

**All endpoints using JWT authentication**
**Code location:** `security/JwtService.java` (throws `InvalidTokenException`); `exception/GlobalExceptionHandler.java`
**Severity:** High

`InvalidTokenException` is a custom exception defined in the codebase, but it has no handler in `GlobalExceptionHandler`. It falls through to the generic handler and produces 500. This can surface if `InvalidTokenException` is thrown outside the filter chain (e.g., in a service). Even within the filter chain, the gap between what is expected to be a handled exception type and the actual fallback to 500 is a correctness defect. A dedicated handler mapping it to 401 should be added.

---

## Finding 17 — DocumentController.list() sort validation: IllegalArgumentException path is correct but fragile

**Endpoint:** `GET /api/documents`
**Code location:** `controller/DocumentController.java` lines 129-138
**Severity:** Info

`validateSort()` throws `IllegalArgumentException` for disallowed sort fields, which `GlobalExceptionHandler` catches and maps to 400. This works correctly. However, `IllegalArgumentException` is a very broad type — any code path between the controller and the handler that happens to throw `IllegalArgumentException` for an unrelated reason will also be silently turned into a client 400. Using a dedicated `InvalidSortFieldException extends IllegalArgumentException` (handled explicitly) would make the intent clearer and prevent accidental 400 responses from library code.

This is also the correct error response (400), so the mechanism is sound.

---

## Finding 18 — UUID path variable mismatches: consistent 400, acceptable

**All path variables typed as UUID**
**Code location:** `exception/GlobalExceptionHandler.java` lines 67-73
**Severity:** Info

`MethodArgumentTypeMismatchException` is handled and returns 400 with a descriptive message. This is consistent across all controllers. No issue.

---

## Finding 19 — CategoryController: uniqueness enforced at service level only, no DB race condition guard at application layer

**Endpoints:** `POST /api/categories`, `PUT /api/categories/{id}`
**Code location:** `service/CategoryService.java` lines 34-37, 49-53
**Severity:** Low

`CategoryService.create()` checks `categoryRepository.existsByName()` before saving; `update()` does likewise. The `categories.name` column has a UNIQUE constraint in the DB (schema line 90), so duplicate inserts will still fail with a DB constraint violation rather than a `CategoryAlreadyExistsException`. Under concurrent requests the check-then-act window means the service-level duplicate check can be bypassed; the DB constraint is the real guard. The `DataIntegrityViolationException` from Spring Data is not handled in `GlobalExceptionHandler` — it would fall through to the generic 500 handler. Adding a handler for `DataIntegrityViolationException` that maps to 409 would make concurrent duplicate category creation return the correct status.

---

## Finding 20 — CreateReadingListRequest / UpdateReadingListRequest: name not size-capped, diverges from DB column

Already covered as Findings 4 and 5. Noted again here for completeness as it mirrors the category name pattern, which does have `@Size(max=255)` on both create and update requests.

---

## Summary Table

| # | Finding | Endpoint(s) | Severity |
|---|---------|-------------|----------|
| 1 | Empty PUT body accepted as no-op | PUT /api/users/{id} | Medium |
| 2 | Whitespace-only displayName accepted | PUT /api/users/{id} | Medium |
| 3 | Whitespace-only password accepted | PUT /api/users/{id} | High |
| 4 | Reading list name has no max length | POST /api/reading-lists | Medium |
| 5 | Reading list name update has no max length | PUT /api/reading-lists/{id} | Medium |
| 6 | Document type is free-text, not enum-bound; CreateArticleRequest.type missing @Size | POST /api/documents, POST /api/documents/article | Medium |
| 7 | search param has no length cap; leads to unbounded LIKE | GET /api/documents | Low |
| 8 | Pageable page size unbounded in 3 controllers | GET /api/documents, /reading-lists, /comments | High |
| 9 | Article body has no max size; heap/DB exhaustion | POST /api/documents/article | Medium |
| 10 | categoryIds set has no size cap; N+1 amplification | POST /api/documents, POST /api/documents/article | Low |
| 11 | File MIME type trusts client Content-Type; stored XSS vector | POST /api/documents | High |
| 12 | File served inline without X-Content-Type-Options or CSP | GET /api/documents/{id}/file | Medium |
| 13 | Private documents can be bookmarked by non-owners | POST /api/reading-lists/{id}/items | Medium |
| 14 | addComment has no @PreAuthorize; auth via service throws 500 not 401 | POST /api/documents/{id}/comments | Medium |
| 15 | UsernameNotFoundException unhandled → 500 | All authenticated endpoints | High |
| 16 | InvalidTokenException unhandled → 500 | All JWT endpoints | High |
| 17 | validateSort uses broad IllegalArgumentException | GET /api/documents | Info |
| 18 | UUID path variable type mismatch → consistent 400 | All UUID path variables | Info |
| 19 | DataIntegrityViolationException on concurrent duplicate category → 500 | POST/PUT /api/categories | Low |
| 20 | (Duplicate of 4/5) reading list name size | POST/PUT /api/reading-lists | Medium |
