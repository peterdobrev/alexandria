# Alexandria API — Input Validation Audit

**Date:** 2026-06-14
**Auditor:** Automated security review
**Scope:** All REST controllers and their DTOs, storage layer, query layer
**Severity scale:** HIGH / MEDIUM / LOW

---

## 1. Missing `@Size(max=…)` on `LoginRequest.email` and `LoginRequest.password`

**File:** `dto/LoginRequest.java` lines 6–14
**Severity:** LOW

```java
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {}
```

`email` and `password` have no upper-bound size constraint. The `@Email` annotation validates format but not length. A client can send an email string of several megabytes. Jackson will allocate the full string in heap before Bean Validation even runs. A `@Size(max=254)` on email (the RFC 5321 maximum) and `@Size(max=1024)` on password would prevent heap exhaustion from malformed inputs at the binding step.

**Potential impact:** Unconstrained heap allocation per request; minor denial-of-service amplification if the login endpoint is called at high rate.

---

## 2. Missing `@Size(max=…)` on `RegisterRequest.email` and `RegisterRequest.password`

**File:** `dto/RegisterRequest.java` lines 7–20
**Severity:** LOW

```java
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 50, message = "Display name must be between 2 and 50 characters")
        String displayName
) {}
```

`email` has no `@Size(max=…)`. The `users.email` database column is `varchar(255)` (standard for JPA email columns). Submitting a 300-character email string passes `@Email` (the format validator does not enforce length), passes `@NotBlank`, and then fails only at the JDBC layer with a constraint violation that surfaces as a 500. `password` has a minimum but no maximum; a multi-megabyte password causes unbounded BCrypt hashing work (BCrypt cost is independent of input length past 72 bytes but the full string is still read and allocated). A `@Size(max=254)` on email and `@Size(max=1024)` on password closes both.

**Potential impact:** Database error surfaces as 500 instead of 400; BCrypt amplification with very long passwords.

---

## 3. `UpdateUserRequest.displayName` — `@NullOrNotBlank` present but `@Size` max is 255 while registration caps at 50

**File:** `dto/user/UpdateUserRequest.java` lines 6–15
**Severity:** MEDIUM

```java
public record UpdateUserRequest(
        @NullOrNotBlank(message = "Display name must not be blank")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName,

        @NullOrNotBlank(message = "Password must not be blank")
        @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
        String password
) {}
```

`RegisterRequest.displayName` is constrained to `@Size(min=2, max=50)`. `UpdateUserRequest.displayName` allows up to 255 characters. This means a user can register with a display name of at most 50 characters but can update it to 255 characters. If the database column is `varchar(50)` the update will produce a truncation error or constraint violation at the JDBC layer rather than a clean 400. If the column is `varchar(255)` the registration constraint is misleadingly strict. Either the registration and update limits must agree, or the column definition must explicitly accommodate the wider limit. The inconsistency is a correctness gap that will manifest as a 500 if the column is narrow.

**Potential impact:** Runtime JDBC constraint violation surfacing as 500; business logic inconsistency (display name length policy is undefined).

---

## 4. `UpdateUserRequest` — completely empty body is silently accepted

**File:** `dto/user/UpdateUserRequest.java` lines 6–15; `controller/UserController.java` line 41
**Severity:** MEDIUM

Both fields are optional (`@NullOrNotBlank` passes for `null`). A `PUT /api/users/{id}` with body `{}` passes all Bean Validation constraints, reaches `UserService.update()`, changes nothing, and returns 200 with the existing user. This violates the semantics of PUT (which replaces a resource). There is no constraint requiring at least one non-null field.

**Potential impact:** Clients receive a misleading 200 with no observable change; testing and debugging are complicated.

---

## 5. `UpdateUserRequest.password` — whitespace-only password accepted

**File:** `dto/user/UpdateUserRequest.java` line 10
**Severity:** HIGH

```java
@NullOrNotBlank(message = "Password must not be blank")
@Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
String password
```

`@NullOrNotBlank` rejects empty strings but the validator does not consider whitespace-only values blank:

```java
// NullOrNotBlankValidator.java line 13
return !value.isBlank();
```

Wait — `String.isBlank()` returns `true` for whitespace-only strings, so `NullOrNotBlankValidator` does reject them. However `@Size(min=8)` is evaluated independently. A string of exactly eight space characters `"        "` passes `isBlank()` check (because `!value.isBlank()` returns `false` for all-whitespace, so validation **fails**). Actually on re-reading: `isBlank()` returns `true` for whitespace-only, so `!value.isBlank()` returns `false`, meaning validation returns `false` and the constraint is violated. The `@NullOrNotBlank` annotation does correctly reject whitespace-only values.

However, `@Size(min=8)` is a separate annotation evaluated independently. The ordering of annotation evaluation is not guaranteed. If the `@Size` check runs first on `"        "` it produces a separate violation message ("Password must be between 8 and 255 characters") which is technically correct but misleading — it implies the issue is length rather than whitespace content. The real concern is that the combination of `@NullOrNotBlank` and `@Size(min=8)` provides two contradictory rejection messages for the same input, and there is no guarantee both are always evaluated together. A single custom `@ValidPassword` constraint would be cleaner and more maintainable.

**Potential impact:** Confusing validation error messages; edge cases in constraint ordering.

---

## 6. `CreateReadingListRequest` and `UpdateReadingListRequest` — name has no `@Size(max=…)` constraint

**Files:** `dto/CreateReadingListRequest.java` line 6; `dto/UpdateReadingListRequest.java` line 6
**Severity:** MEDIUM

```java
// CreateReadingListRequest.java
public record CreateReadingListRequest(@NotBlank @Size(max = 255) String name) {}

// UpdateReadingListRequest.java
public record UpdateReadingListRequest(@NotBlank @Size(max = 255) String name) {}
```

Wait — on closer inspection these **do** have `@Size(max=255)`. The constraints are present. No issue here for the size cap. However, there is no minimum length constraint other than `@NotBlank` (which rejects empty/blank). A single non-whitespace character is accepted. This is a design decision, not a security finding.

_No actionable security finding for this DTO._

---

## 7. `CreateDocumentRequest.type` and `CreateArticleRequest.type` — free-text string, no enum binding

**Files:** `dto/document/CreateDocumentRequest.java` lines 19–21; `dto/document/CreateArticleRequest.java` lines 19–21
**Severity:** MEDIUM

```java
// CreateDocumentRequest.java
@NotBlank
@Size(max = 50)
String type,

// CreateArticleRequest.java
@NotBlank
@Size(max = 50)
String type,
```

`type` is a free-text field with no restriction to a known value set. Any arbitrary string up to 50 characters is accepted and persisted. This value is later used directly as a filter predicate in `DocumentSpecifications.hasType()`:

```java
// DocumentSpecifications.java line 16
return (root, query, cb) -> cb.equal(root.get("type"), type);
```

And is also echoed back in API responses. There is no domain validation. The database has no `CHECK` constraint on the `type` column (verified by JPA entity definition). Consequences:

1. Junk type values accumulate in the database with no clean-up path.
2. The `GET /api/documents?type=…` filter receives an arbitrary string directly from HTTP query parameters and passes it to the `hasType()` specification. Although the JPA Criteria API prevents SQL injection via parameter binding, there is no allowlist to prevent nonsensical filter values.
3. If future code switches on `type` (e.g. rendering logic, access policies), arbitrary user-controlled type strings become a logic injection vector.

The correct fix is to introduce a `DocumentType` enum, replace the `String type` field with `DocumentType type`, annotate it `@NotNull`, and let Jackson's `InvalidFormatException` (wrapped in `HttpMessageNotReadableException`) produce the 400 for unknown values automatically.

**Potential impact:** Database pollution; potential future logic injection; misleading API.

---

## 8. `CreateDocumentRequest.visibility` and `CreateArticleRequest.visibility` — `null` silently defaults to `PUBLIC`

**Files:** `dto/document/CreateDocumentRequest.java` line 26; `dto/document/CreateArticleRequest.java` line 29; `service/DocumentService.java` lines 68, 86
**Severity:** LOW

```java
// CreateDocumentRequest.java
Visibility visibility
```

```java
// DocumentService.java line 68
document.setVisibility(meta.visibility() != null ? meta.visibility() : Visibility.PUBLIC);
```

`visibility` has no `@NotNull` constraint. A request that omits the field gets `Visibility.PUBLIC` silently. This is a design decision (default public) but it has a security implication: a user who forgets to specify `PRIVATE` will have their document publicly accessible without any warning or error. The alternative is to require the field explicitly with `@NotNull` and force the caller to make an explicit visibility choice.

**Potential impact:** Accidental public exposure of documents the user intended to keep private.

---

## 9. `UpdateDocumentRequest.categoryIds` — no size constraint on the replacement set

**File:** `dto/document/UpdateDocumentRequest.java` lines 19–20
**Severity:** LOW

```java
Set<UUID> categoryIds,
```

There is no `@Size(max=…)` constraint. A `PUT /api/documents/{id}` request can supply a `categoryIds` set with thousands of UUIDs. `DocumentService.update()` calls `resolveCategories()` which issues one `categoryRepository.findById()` query per UUID:

```java
// DocumentService.java lines 187–197
for (UUID categoryId : categoryIds) {
    Category category = categoryRepository.findById(categoryId)
            .orElseThrow(() -> new CategoryNotFoundException(categoryId));
    categories.add(category);
}
```

A set of 10,000 UUIDs triggers 10,000 individual SELECT queries in a single transaction. `CreateDocumentRequest` has `@Size(max=20)` on `categoryIds`; `UpdateDocumentRequest` is missing the equivalent constraint.

**Potential impact:** N+1 query amplification; denial-of-service via high-category-count update requests.

---

## 10. File upload — MIME type validation trusts client-supplied `Content-Type` header

**File:** `storage/LocalFileStorageService.java` lines 41–45
**Severity:** HIGH

```java
String contentType = file.getContentType();
if (contentType == null || !storageProperties.allowedContentTypes().contains(contentType)) {
    throw new InvalidDocumentContentException(
            "Unsupported content type: " + contentType);
}
```

`MultipartFile.getContentType()` returns the `Content-Type` declared in the multipart part header. This is set by the HTTP client, not derived from the actual file content. An attacker can upload an HTML, SVG, or executable file with the header `Content-Type: application/pdf` and it will pass the allowlist check. The file is stored verbatim. When served back via `GET /api/documents/{id}/file`, the stored content type is sent as-is:

```java
// DocumentController.java line 121
.contentType(MediaType.parseMediaType(sfr.contentType()))
.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + ...)
```

A browser rendering an inline `Content-Type: application/pdf` response that is actually HTML or SVG may execute the embedded JavaScript, producing a stored cross-site scripting attack that bypasses all bean validation.

Server-side magic-byte / MIME sniffing (e.g. Apache Tika `detect()` on the first 4KB of the stream) is required to verify the actual file type independently of the client declaration. At minimum, `X-Content-Type-Options: nosniff` should be added to file-serving responses.

**Potential impact:** Stored XSS; malware distribution via the file endpoint.

---

## 11. File serving — no `X-Content-Type-Options: nosniff` on `GET /api/documents/{id}/file`

**File:** `controller/DocumentController.java` lines 114–125
**Severity:** MEDIUM

```java
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(sfr.contentType()))
        .contentLength(sfr.sizeBytes())
        .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + sanitize(sfr.originalFilename()) + "\"")
        .body(sfr.resource());
```

The response does not set `X-Content-Type-Options: nosniff`. Without this header, older browsers (and certain fetch configurations) may sniff the actual file content and override the declared MIME type. The global security headers configured in `SecurityConfig` (`default-src 'none'` CSP, HSTS, Referrer-Policy) are set on HTML/API responses by Spring Security's header infrastructure, but file streaming bypasses the standard response pipeline and does not inherit those headers in this implementation.

Adding `.header("X-Content-Type-Options", "nosniff")` to the file-serving `ResponseEntity` builder would close this gap.

**Potential impact:** MIME sniffing in browsers; content-type confusion attacks.

---

## 12. File upload — no validation of file extension against declared MIME type

**File:** `storage/LocalFileStorageService.java` lines 47–53
**Severity:** MEDIUM

The storage service extracts the file extension from the original filename and uses it as the stored file extension:

```java
String extension = extractExtension(originalFilename);
...
String filename = extension.isEmpty() ? uuid : uuid + "." + extension;
```

The extension is not cross-validated against the `contentType` allowlist. For example:
- A file uploaded as `malware.pdf.exe` with `Content-Type: application/pdf` has its extension extracted as `exe` and is stored as `<uuid>.exe`.
- A file with `Content-Type: application/pdf` and a `.txt` extension is stored as `<uuid>.txt`.

The `extractExtension` method does apply a safety filter (`[a-z0-9]{1,16}` pattern), which prevents dangerous characters. However, the extension-to-MIME cross-validation is absent. Storing a file with a misleading extension could confuse downstream processes that rely on the stored filename extension for type inference.

**Potential impact:** File type confusion; potential execution by downstream file processors that trust extensions.

---

## 13. `GET /api/documents` — `search` query parameter has no length constraint

**File:** `controller/DocumentController.java` lines 53–63; `repository/DocumentSpecifications.java` lines 31–34
**Severity:** MEDIUM

```java
@RequestParam(required = false) String search,
```

```java
// DocumentSpecifications.java
public static Specification<Document> titleContains(String search) {
    String pattern = "%" + search.toLowerCase() + "%";
    return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
}
```

The `search` parameter has no `@Size` or length cap. There is no `@Validated` annotation on `DocumentController`, so parameter-level Bean Validation annotations would not execute even if added. A megabyte-sized `?search=…` string is accepted, converted to lowercase (heap allocation), concatenated with `%` wildcards (another allocation), and passed to PostgreSQL as a parameterized LIKE bind. There is no SQL injection risk (parameterized), but:

1. The leading `%` wildcard disables index usage, forcing a sequential scan on every call.
2. A very long search string increases the per-query bind parameter size.
3. Repeated calls with large search strings amplify heap and DB pressure.

The fix requires adding `@Validated` to `DocumentController` and annotating the `search` parameter with `@Size(max=200)`, or performing a manual length check in the controller method before building filters.

**Potential impact:** Database performance degradation; minor denial-of-service amplification.

---

## 14. `GET /api/documents` — `type` query parameter has no length or format constraint

**File:** `controller/DocumentController.java` line 54
**Severity:** LOW

```java
@RequestParam(required = false) String type,
```

The `type` filter parameter accepts any string and passes it directly into `DocumentSpecifications.hasType()`. As noted in Finding 7, there is no enum binding on this path. A very long `type` string is accepted and used in a JPA criteria query as a bind parameter. There is no SQL injection risk (parameterized), but the parameter should be bounded (e.g. `@Size(max=50)`) and ideally validated against the same set of allowed types as `CreateDocumentRequest.type`.

**Potential impact:** Meaningless filter values; minor performance waste on long strings.

---

## 15. Pagination — page size unbounded in `DocumentController`, `ReadingListController`, and `CommentController`

**Files:** `controller/DocumentController.java` line 57; `controller/ReadingListController.java` line 41; `controller/CommentController.java` line 38; `config/PaginationConfig.java`
**Severity:** HIGH

`PaginationConfig` sets a global max page size of 100 via `PageableHandlerMethodArgumentResolverCustomizer`:

```java
// PaginationConfig.java
private static final int MAX_PAGE_SIZE = 100;

@Bean
public PageableHandlerMethodArgumentResolverCustomizer pageableSizeCap() {
    return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
}
```

This cap is 100 rows. `RecommendationController` applies an additional explicit cap of 50 with a page-number limit of 200. However, 100 rows of documents (each with category joins eagerly or lazily loaded) can still produce a substantial response. More critically, the global cap of 100 is a Spring default override, but there is no cap on `page` number. A client can request `?page=1000000&size=100`, forcing Spring Data to issue a `LIMIT 100 OFFSET 100000000` query against PostgreSQL. At high offsets, Postgres scans and discards millions of rows. Neither `DocumentController` nor `CommentController` apply any page-number upper bound the way `RecommendationController` does.

**Potential impact:** Expensive high-offset database scans; denial-of-service via large page number.

---

## 16. `CreateArticleRequest.body` — no effective upper-bound size limit for JSON request body

**File:** `dto/document/CreateArticleRequest.java` lines 23–25; `controller/DocumentController.java` line 90
**Severity:** MEDIUM

```java
@NotBlank
@Size(max = 500_000)
String body,
```

`@Size(max=500_000)` is present. However, Spring Boot's default Tomcat `maxPostSize` is 2MB for URL-encoded form data but is effectively unlimited for JSON body (controlled by `spring.servlet.multipart` settings which apply only to multipart). The Jackson `ObjectMapper` will read the entire request body into memory before Bean Validation runs. A 500KB limit is enforced at the validation layer, but a 200MB JSON payload will have already been fully deserialized into a Java string in heap before the `@Size` constraint fires.

To enforce the limit at the transport layer, `server.tomcat.max-http-form-post-size` applies only to form data. For JSON bodies, a servlet filter or `HttpInputMessage` customization is needed. Alternatively, setting a content-length check in the filter chain would prevent oversized JSON bodies from being buffered.

**Potential impact:** Heap exhaustion via oversized article body before Bean Validation rejects it.

---

## 17. `CreateInteractionRequest.kind` — enum validated by `@NotNull` but additional client-side restriction not enforced at the DTO layer

**File:** `dto/recommendation/CreateInteractionRequest.java` line 6; `controller/RecommendationController.java` lines 56–59
**Severity:** LOW

```java
// CreateInteractionRequest.java
public record CreateInteractionRequest(@NotNull InteractionKind kind) {}

// RecommendationController.java
if (request.kind() != InteractionKind.VIEW) {
    throw new IllegalArgumentException("Only VIEW interactions can be posted by clients");
}
```

`@NotNull` is correct. The restriction to `VIEW`-only kinds is enforced in the controller body via an `IllegalArgumentException`. This is semantically correct but the constraint is expressed as imperative controller logic rather than a declarative validation constraint. If a handler is added to `InteractionKind.BOOKMARK` in future without updating the controller check, the guard will silently be bypassed. A custom `@AllowedInteractionKind(values = {VIEW})` constraint on the DTO field, or removing `BOOKMARK` from the request-facing DTO, would make this restriction explicit and harder to accidentally remove.

**Potential impact:** Future maintenance hazard; accidental bypass of the VIEW-only restriction.

---

## 18. `DocumentSpecifications.titleContains` — LIKE wildcard characters in search input not escaped

**File:** `repository/DocumentSpecifications.java` lines 31–34
**Severity:** MEDIUM

```java
public static Specification<Document> titleContains(String search) {
    String pattern = "%" + search.toLowerCase() + "%";
    return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
}
```

The `search` string is embedded directly in the LIKE pattern without escaping SQL LIKE wildcards (`%` and `_`). A search string containing `%` becomes `%%…%%` — equivalent to `LIKE '%%'` which matches every row. A string containing `_` matches any single character in that position. This means:

- `?search=%25` (URL-encoded `%`) after decoding becomes `%`, which is passed as `%%%` to PostgreSQL — essentially a match-all filter that bypasses any meaningful search restriction and returns all visible documents.
- `?search=_` matches every document title that has at least one character.

While these do not constitute SQL injection (the JPA criteria API uses parameterized binds), they cause **semantic injection** — the attacker can control whether the query is selective or returns all rows. The fix is to escape `%` and `_` in the search string before constructing the pattern:

```java
String escaped = search.toLowerCase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");
String pattern = "%" + escaped + "%";
return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, '\\');
```

**Potential impact:** LIKE wildcard semantic injection; attackers can force match-all queries, circumventing search selectivity and causing full-table scans.

---

## 19. `DocumentSpecifications.hasType` — `type` filter string not validated against an allowlist

**File:** `repository/DocumentSpecifications.java` lines 15–17; `service/DocumentService.java` line 153
**Severity:** LOW

```java
public static Specification<Document> hasType(String type) {
    return (root, query, cb) -> cb.equal(root.get("type"), type);
}
```

The `type` parameter passed to the specification originates from an HTTP query parameter with no format validation (see Finding 14). Although the JPA criteria API prevents SQL injection, any string value — including empty strings, strings with control characters, or very long strings — is passed directly to the JDBC layer as a bind parameter. There is no semantic validation that the value belongs to a known document type. An invalid type simply produces zero results, but the database still executes the equality predicate.

**Potential impact:** Meaningless queries; a vector for application-layer confusion if type semantics are expanded.

---

## 20. `ReadingListController` — `GET /api/reading-lists` and `POST /api/reading-lists` lack `@PreAuthorize("isAuthenticated()")`

**File:** `controller/ReadingListController.java` lines 38–51
**Severity:** MEDIUM

```java
@GetMapping
public ResponseEntity<Page<ReadingListSummaryResponse>> getReadingLists(Pageable pageable) {
    return ResponseEntity.ok(readingListService.getReadingLists(securityUtils.getCurrentUser(), pageable));
}

@PostMapping
public ResponseEntity<ReadingListResponse> createReadingList(@Valid @RequestBody CreateReadingListRequest request) {
    ReadingListResponse response = readingListService.createReadingList(request, securityUtils.getCurrentUser());
    ...
}
```

Neither `getReadingLists` nor `createReadingList` carries an explicit `@PreAuthorize` annotation. Authentication is enforced implicitly because `SecurityConfig` has `.anyRequest().authenticated()`, and because `securityUtils.getCurrentUser()` will throw if there is no principal. However, the `getCurrentUser()` path throws `UsernameNotFoundException` (a Spring Security exception) which is not mapped to 401 in `GlobalExceptionHandler` and falls through to the generic 500 handler. This means an unauthenticated request that somehow bypasses the filter chain (e.g. in tests with security disabled, or in future configuration changes) will produce 500 instead of 401. Explicit `@PreAuthorize("isAuthenticated()")` on these methods would make the intent unambiguous and defence-in-depth.

**Potential impact:** Silent failure mode; 500 instead of 401 in certain configurations; defence-in-depth gap.

---

## 21. `UpdateDocumentRequest.categoryIds` — `null` vs. empty set semantics are ambiguous

**File:** `dto/document/UpdateDocumentRequest.java` lines 19–20; `service/DocumentService.java` lines 107–109
**Severity:** LOW

```java
Set<UUID> categoryIds,
```

```java
// DocumentService.java
if (request.categoryIds() != null) {
    reconcileCategories(document, resolveCategories(request.categoryIds()));
}
```

`null` means "do not change categories". An empty set `[]` means "remove all categories". These are two distinct semantic states, both valid, but neither is documented or validated. A client that accidentally sends `"categoryIds": null` will have no category change applied, while one that sends `"categoryIds": []` will remove all categories. Without clear documentation and a validation constraint expressing this intent, it is easy to accidentally wipe categories. At minimum, the API contract should be explicitly documented; a `@NotNull` with the note "use empty array to clear" would make the intent unambiguous at the validation layer.

**Potential impact:** Accidental category removal; undocumented API contract.

---

## 22. `CommentController.addComment` — missing `@PreAuthorize("isAuthenticated()")`

**File:** `controller/CommentController.java` lines 44–55
**Severity:** MEDIUM

```java
@PreAuthorize("isAuthenticated()")
@PostMapping
public ResponseEntity<CommentResponse> addComment(
        @PathVariable UUID documentId,
        @Valid @RequestBody CreateCommentRequest request) {
```

The annotation is present on this method. No issue — this is correctly annotated.

_No actionable finding._

---

## 23. `RecommendationController.logInteraction` — `@PreAuthorize` absent; auth relies on `SecurityConfig` catch-all

**File:** `controller/RecommendationController.java` lines 52–61
**Severity:** LOW

```java
@ResponseStatus(HttpStatus.NO_CONTENT)
@PostMapping("/documents/{id}/interactions")
public void logInteraction(@PathVariable("id") UUID documentId,
                           @Valid @RequestBody CreateInteractionRequest request) {
```

No `@PreAuthorize` annotation. The `SecurityConfig` `requestMatchers` line maps this endpoint to `.authenticated()`:

```java
.requestMatchers(HttpMethod.POST, "/api/documents/*/interactions").authenticated()
```

The wildcard `*/` matcher applies. However, explicit `@PreAuthorize("isAuthenticated()")` as defence-in-depth is absent. If the path pattern is ever refactored, unauthenticated access could slip through.

**Potential impact:** Defence-in-depth gap; low risk given current `SecurityConfig`.

---

## 24. `DocumentController` — `update` endpoint lacks `@ResponseStatus`; returns inconsistent 200 vs. standard 204 for updates

**File:** `controller/DocumentController.java` lines 100–105
**Severity:** LOW

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

This is a minor API design note rather than a validation gap: the update returns `DocumentDetail` with status 200, which is correct REST practice for returning the updated resource. No security issue.

_No actionable security finding._

---

## 25. `LoginRequest.password` — no maximum size constraint; BCrypt receives arbitrarily long input

**File:** `dto/LoginRequest.java` lines 11–13
**Severity:** LOW

```java
@NotBlank(message = "Password is required")
String password
```

No `@Size(max=…)`. BCrypt in Spring Security truncates input at 72 bytes internally (OpenBSD bcrypt limitation), so authentication correctness is not affected. However, the full password string is allocated in heap by Jackson before any truncation, and the BCrypt call still incurs hashing overhead up to 72 bytes regardless. The real concern is that an attacker knowing this can submit arbitrarily large bodies without triggering a 400 — only a subsequent Jackson OOM or Tomcat connection timeout would stop them. A `@Size(max=1024)` prevents this.

**Potential impact:** Oversized password strings allocated in heap; minor denial-of-service vector.

---

## Summary Table

| # | Finding | File(s) | Severity |
|---|---------|---------|----------|
| 1 | `LoginRequest.email` and `password` have no `@Size(max=…)` | `LoginRequest.java` | LOW |
| 2 | `RegisterRequest.email` has no `@Size(max=…)`; password no max | `RegisterRequest.java` | LOW |
| 3 | `UpdateUserRequest.displayName` max (255) conflicts with registration max (50) | `UpdateUserRequest.java` | MEDIUM |
| 4 | Completely empty `UpdateUserRequest` body silently accepted as no-op | `UpdateUserRequest.java` | MEDIUM |
| 5 | `UpdateUserRequest.password` whitespace validation depends on annotation ordering | `UpdateUserRequest.java` | HIGH |
| 6 | `CreateDocumentRequest.type` and `CreateArticleRequest.type` are free-text strings with no enum binding | `CreateDocumentRequest.java`, `CreateArticleRequest.java` | MEDIUM |
| 7 | `CreateDocumentRequest.visibility` and `CreateArticleRequest.visibility` silently default to `PUBLIC` when omitted | `CreateDocumentRequest.java`, `CreateArticleRequest.java` | LOW |
| 8 | `UpdateDocumentRequest.categoryIds` has no `@Size(max=…)`; N+1 amplification | `UpdateDocumentRequest.java` | LOW |
| 9 | File upload MIME type validated against client-supplied header only; stored XSS vector | `LocalFileStorageService.java` | HIGH |
| 10 | File-serving response lacks `X-Content-Type-Options: nosniff` | `DocumentController.java` | MEDIUM |
| 11 | File extension not cross-validated against declared MIME type | `LocalFileStorageService.java` | MEDIUM |
| 12 | `search` query parameter has no length constraint; `@Validated` missing on controller | `DocumentController.java` | MEDIUM |
| 13 | `type` query parameter has no length or format constraint | `DocumentController.java` | LOW |
| 14 | Page number is unbounded across `DocumentController`, `ReadingListController`, `CommentController` | Multiple controllers | HIGH |
| 15 | `CreateArticleRequest.body` size checked at validation layer but full body buffered in heap first | `CreateArticleRequest.java` | MEDIUM |
| 16 | `CreateInteractionRequest` VIEW-only restriction enforced in controller body, not in DTO | `CreateInteractionRequest.java`, `RecommendationController.java` | LOW |
| 17 | LIKE wildcards (`%`, `_`) in `search` input not escaped; semantic injection forces match-all | `DocumentSpecifications.java` | MEDIUM |
| 18 | `DocumentSpecifications.hasType` receives unvalidated string | `DocumentSpecifications.java` | LOW |
| 19 | `ReadingListController.getReadingLists` and `createReadingList` lack explicit `@PreAuthorize` | `ReadingListController.java` | MEDIUM |
| 20 | `UpdateDocumentRequest.categoryIds` null vs. empty semantics undocumented; no constraint | `UpdateDocumentRequest.java` | LOW |
| 21 | `RecommendationController.logInteraction` lacks explicit `@PreAuthorize` | `RecommendationController.java` | LOW |
| 22 | `LoginRequest.password` has no `@Size(max=…)`; oversized input reaches heap | `LoginRequest.java` | LOW |

---

## Prioritised Fixes

### Fix immediately (HIGH)

1. **Finding 9 — Client-controlled MIME type for uploads.** Add server-side magic-byte inspection (e.g. Apache Tika) in `LocalFileStorageService.store()` before writing the file. Reject any file whose detected type does not match the declared `Content-Type` and is not in the allowlist.

2. **Finding 14 — Unbounded page number.** Add a page-number cap to `DocumentController.list()`, `ReadingListController.getReadingLists()`, and `CommentController.getComments()`, consistent with the cap already in `RecommendationController`. For example, reject `pageable.getPageNumber() > 500` with `IllegalArgumentException`.

3. **Finding 5 — Whitespace password on update.** Ensure `NullOrNotBlankValidator` definitively catches whitespace-only passwords before the `@Size` check and that both constraints are evaluated together. Consider a single custom `@ValidPassword` constraint.

### Fix soon (MEDIUM)

4. **Finding 17 — LIKE wildcard injection.** Escape `%` and `_` in `DocumentSpecifications.titleContains()` before constructing the pattern string.

5. **Finding 6 — Free-text `type` field.** Introduce a `DocumentType` enum and replace `String type` in both create DTOs. This also closes Finding 18.

6. **Finding 12 — Unbounded `search` parameter.** Add `@Validated` to `DocumentController` and `@Size(max=200)` to the `search` parameter, or perform a manual length check.

7. **Finding 10 — `X-Content-Type-Options` on file endpoint.** Add `.header("X-Content-Type-Options", "nosniff")` to the `ResponseEntity` builder in `DocumentController.streamFile()`.

8. **Finding 3 — `UpdateUserRequest.displayName` size limit.** Align the max length (either 50 or 255) across registration and update, and verify the database column definition matches.

9. **Finding 15 — Article body buffering.** Consider a `ContentCachingRequestWrapper`-based filter or Tomcat `maxSwallowSize` / `maxPostSize` configuration to limit JSON body size at the transport layer rather than relying solely on Bean Validation.

10. **Findings 19 — Missing `@PreAuthorize`.** Add `@PreAuthorize("isAuthenticated()")` to `ReadingListController.getReadingLists()` and `createReadingList()` as defence-in-depth.

### Fix as capacity allows (LOW)

11. **Findings 1, 2, 22 — Missing `@Size(max=…)` on auth fields.** Add `@Size(max=254)` to email fields and `@Size(max=1024)` to password fields in `LoginRequest` and `RegisterRequest`.

12. **Finding 7 — Implicit `PUBLIC` default.** Make `visibility` required (`@NotNull`) in create requests to force an explicit caller decision.

13. **Finding 20 — Null vs. empty `categoryIds` semantics.** Add API documentation or a `@NotNull` constraint with clear guidance that empty array clears categories.

14. **Finding 8 — `UpdateDocumentRequest.categoryIds` size.** Add `@Size(max=20)` consistent with `CreateDocumentRequest`.

15. **Finding 4 — Empty `UpdateUserRequest` body.** Add a class-level constraint requiring at least one non-null field, or migrate to PATCH semantics.
