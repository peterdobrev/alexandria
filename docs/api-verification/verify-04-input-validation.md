# Adversarial Verification of `04-input-validation.md`

**Verifier:** Adversarial review (manual, source-first)
**Date:** 2026-06-14
**Method:** Every source file cited in the audit was read in full. Each claim was checked against the actual code before a verdict was issued.

---

## Verdicts by Finding Number

The audit report uses an internal numbering that does not match its own summary table (the narrative has 25 sections but some are self-refuted, and the summary table renumbers findings 1–22). Verdicts below follow the **summary table numbers (1–22)** with the narrative section number noted in parentheses.

---

### Finding 1 (narrative §1) — `LoginRequest.email` and `password` missing `@Size(max=…)`

**Verdict: CONFIRMED (low severity)**

`LoginRequest.java` has `@NotBlank @Email` on `email` and `@NotBlank` on `password`. There is no `@Size(max=…)` on either field. The audit's claim is accurate. However, the severity escalation reasoning contains a factual error: the claim that "Jackson will allocate the full string in heap before Bean Validation even runs" is true but the described DoS amplification is weak — Tomcat's `server.tomcat.max-swallow-size` and the default connector `maxPostSize` (2 MB for form data, configurable for JSON) still apply at the transport layer. The missing `@Size` is a genuine gap but the practical risk is lower than implied.

**What is wrong in the report:** The report duplicates this finding verbatim in narrative §25 (summary table entry 22). Both say the same thing about `LoginRequest.password`. That is not a second distinct finding — it is a copy.

---

### Finding 2 (narrative §2) — `RegisterRequest.email` missing `@Size(max=…)`; `password` has no max

**Verdict: CONFIRMED**

`RegisterRequest.java` confirms: `email` has `@NotBlank @Email` with no `@Size`; `password` has `@NotBlank @Size(min=8)` with no max. The BCrypt amplification argument is correct in principle (the full string is allocated; BCrypt internally truncates at 72 bytes, but the JVM still holds the allocation). The 500-vs-400 argument for email is also correct: a 300-character email passes all Bean Validation constraints and fails only at the JDBC layer.

---

### Finding 3 (narrative §3) — `UpdateUserRequest.displayName` max 255 vs. registration max 50

**Verdict: CONFIRMED**

`RegisterRequest.displayName` is `@Size(min=2, max=50)`. `UpdateUserRequest.displayName` is `@Size(max=255)`. The inconsistency is real. Whether the database column is `varchar(50)` or `varchar(255)` determines whether this is a runtime-error risk or just a policy inconsistency; the audit correctly flags both possibilities. The finding is accurate.

---

### Finding 4 (narrative §4) — Empty `UpdateUserRequest` body silently accepted

**Verdict: CONFIRMED (with severity disagreement)**

`UpdateUserRequest` has two optional fields both annotated `@NullOrNotBlank`. A body of `{}` produces two `null` values. The `NullOrNotBlankValidator.isValid()` returns `true` for `null`, so Bean Validation passes. `UserController.updateUser()` calls `@Valid @RequestBody UpdateUserRequest request` — `@Valid` is present (line 41), so validation does fire. A `{}` body does reach `UserService.update()` and returns 200 with no change.

The audit correctly identifies this. However, the severity classification of MEDIUM is debatable: a no-op 200 is a user-experience issue, not a security vulnerability. No data is modified or leaked. The audit does not overstate the security risk, though the framing ("violates the semantics of PUT") is a design opinion rather than a security gap.

---

### Finding 5 (narrative §5) — `UpdateUserRequest.password` whitespace-only accepted

**Verdict: REFUTED**

The audit is self-contradictory and ultimately reaches the wrong conclusion about what gap actually exists.

The audit states: "`@NullOrNotBlank` rejects empty strings but the validator does not consider whitespace-only values blank". It then immediately corrects itself: "`String.isBlank()` returns `true` for whitespace-only strings, so `!value.isBlank()` returns `false`, meaning validation returns `false` and the constraint is violated." This correction is correct.

Reading `NullOrNotBlankValidator.java` directly:

```java
return !value.isBlank();
```

`"        ".isBlank()` is `true`, so `!true` = `false`, so the validator returns `false` (constraint violated). A whitespace-only password **is rejected**. There is no gap here.

The residual "finding" the audit retreats to — that two constraint annotations may produce confusing error messages if evaluated independently — is not a security issue and is not a code defect. Bean Validation collects all violations; both `@NullOrNotBlank` and `@Size` violations are reported together. The complaint that the messages are "contradictory" is subjective: for `"        "` (8 spaces), `@NullOrNotBlank` fires (whitespace rejection) and `@Size(min=8)` does NOT fire (8 characters meets the minimum), so there is exactly one error message. For `"   "` (3 spaces), both would fire: one says "must not be blank", the other says too short. This is slightly redundant but not incorrect or dangerous.

**The audit's HIGH severity rating is entirely unjustified.** The actual validator correctly rejects whitespace-only passwords. There is no security gap.

---

### Finding 6 (narrative §7) — `CreateDocumentRequest.type` and `CreateArticleRequest.type` are free-text strings

**Verdict: CONFIRMED**

Both DTOs have `@NotBlank @Size(max=50) String type`. There is no enum type. This is confirmed. The SQL injection risk is correctly dismissed (parameterized queries). The concerns about database pollution and future logic injection are legitimate design observations. The finding is accurately described.

---

### Finding 7 (narrative §8) — `CreateDocumentRequest.visibility` silently defaults to `PUBLIC`

**Verdict: CONFIRMED**

`CreateDocumentRequest.visibility` has no annotation. `DocumentService.create()` line 68: `document.setVisibility(meta.visibility() != null ? meta.visibility() : Visibility.PUBLIC)`. Confirmed. The audit correctly notes this is a design decision with a security implication (accidental public exposure), not a code bug per se.

---

### Finding 8 (narrative §9) — `UpdateDocumentRequest.categoryIds` has no `@Size(max=…)`

**Verdict: CONFIRMED**

`UpdateDocumentRequest.java` line 19: `Set<UUID> categoryIds` with no annotation. `CreateDocumentRequest` does have `@Size(max=20)`. `DocumentService.resolveCategories()` iterates with individual `categoryRepository.findById()` calls per UUID (lines 187–196 confirmed in source). The N+1 amplification risk is real. The finding is accurate.

---

### Finding 9 (narrative §10) — File upload MIME type validated against client-supplied header only

**Verdict: CONFIRMED**

`LocalFileStorageService.store()` line 41–45: `file.getContentType()` is checked against `storageProperties.allowedContentTypes()`. `MultipartFile.getContentType()` returns the value from the multipart `Content-Type` header, which is caller-controlled. No server-side content sniffing (e.g. Tika) is performed.

The stored XSS claim is also confirmed: `DocumentController.streamFile()` lines 119–124 builds a `ResponseEntity` with `ContentType` set from `sfr.contentType()`, which originated from the stored `contentType` field, which was populated from the attacker-supplied header. The `Content-Disposition: inline` further increases the risk.

**HIGH severity is correctly assigned.**

---

### Finding 10 (narrative §11) — File-serving response lacks `X-Content-Type-Options: nosniff`

**Verdict: CONFIRMED, but partially overstated**

`DocumentController.streamFile()` lines 119–124 does not add `X-Content-Type-Options: nosniff` to the `ResponseEntity`. That is confirmed.

However, the audit claims "file streaming bypasses the standard response pipeline and does not inherit those headers in this implementation." This is **partially incorrect**. Spring Security's `HeaderWriterFilter` applies headers to every HTTP response that passes through the filter chain, including file-serving responses. The default headers Spring Security adds include `X-Content-Type-Options: nosniff` unless explicitly disabled. Looking at `SecurityConfig.java`: the `.headers()` configuration explicitly sets HSTS, Referrer-Policy, and CSP — but does **not** call `.contentTypeOptions().disable()`. Therefore the default `X-Content-Type-Options: nosniff` from Spring Security's `XContentTypeOptionsHeaderWriter` IS present on all responses including `streamFile`.

**The finding is refuted for the specific header it names.** `X-Content-Type-Options: nosniff` is already applied globally by Spring Security's default header configuration. Adding it again in the `ResponseEntity` builder would be redundant but harmless. The underlying concern (MIME confusion if content type is attacker-controlled, Finding 9) is still valid, but this specific finding as written is wrong about the header being absent.

---

### Finding 11 (narrative §12) — File extension not cross-validated against declared MIME type

**Verdict: CONFIRMED, but the severity is overstated**

`LocalFileStorageService.extractExtension()` extracts the extension from `sanitizeFilename(originalFilename)` and applies only the pattern `[a-z0-9]{1,16}`. There is no cross-check between the extension and the `contentType`. The example of `malware.pdf.exe` with `Content-Type: application/pdf` storing as `<uuid>.exe` is correct given the `extractExtension` logic (it takes the last dot-separated segment; `malware.pdf.exe` → extension `exe`).

However, `sanitizeFilename` (lines 111–127) first strips the path component and then replaces all characters outside `[A-Za-z0-9._-]` with `_`. This does not remove double extensions. So `malware.pdf.exe` would survive sanitization as `malware.pdf.exe` and then `extractExtension` would return `exe` (the last segment after the final dot), not `pdf`. The audit's specific example is accurate.

The practical risk depends on whether the stored file is ever executed or interpreted server-side. On a `LocalFileStorageService`, files are served via `FileSystemResource` through Spring, not executed. The downstream execution concern is theoretical but worth noting as the audit does.

---

### Finding 12 (narrative §13) — `search` query parameter has no length constraint; `@Validated` missing

**Verdict: CONFIRMED**

`DocumentController.java` line 57: `@RequestParam(required = false) String search`. There is no `@Size` or length cap. The controller class declaration is `@RestController @RequestMapping("/api/documents") @RequiredArgsConstructor` — no `@Validated`. Without `@Validated` on the class, parameter-level Bean Validation annotations would not be processed by the method argument resolver. The leading `%` wildcard preventing index use is also correct (confirmed in `DocumentSpecifications.titleContains`). This finding is accurate.

---

### Finding 13 (narrative §14) — `type` query parameter has no length or format constraint

**Verdict: CONFIRMED**

`DocumentController.java` line 54: `@RequestParam(required = false) String type`. No validation. Same `@Validated`-absence issue as Finding 12. No SQL injection risk (confirmed: JPA criteria API, parameterized). The finding is accurate.

---

### Finding 14 (narrative §15) — Page number unbounded

**Verdict: PARTIALLY CORRECT**

The audit confirms `PaginationConfig` sets `MAX_PAGE_SIZE = 100` via `setMaxPageSize()`. This correctly caps the `size` parameter globally for all `Pageable` endpoints.

The audit's claim about page **number** being unbounded is accurate: `PaginationConfig` only sets `setMaxPageSize()`. There is no `setMaxPageNumber()` call, and the `PageableHandlerMethodArgumentResolverCustomizer` API does not enforce page number limits (Spring Data does not provide that capability via the customizer). `RecommendationController` does manually check `pageable.getPageNumber() > MAX_PAGE_NUMBER` (line 44–47). `DocumentController`, `ReadingListController`, and `CommentController` do not perform this check.

However, the audit's framing of this as a HIGH severity issue is aggressive. High-offset pagination is a standard performance concern present in virtually every paginated API; it requires a coordinated large number of requests to trigger meaningful load. The severity characterisation is the audit's opinion, but the factual core is correct.

**Minor error:** The audit states the global cap is a "Spring default override" and implies it may not be reliable. This is wrong: `PageableHandlerMethodArgumentResolverCustomizer` is the documented Spring Data mechanism; `setMaxPageSize()` is unconditionally enforced by `PageableHandlerMethodArgumentResolver` before the controller method is called.

---

### Finding 15 (narrative §16) — `CreateArticleRequest.body` buffered in heap before `@Size` fires

**Verdict: CONFIRMED, with caveats**

`CreateArticleRequest.body` is `@NotBlank @Size(max=500_000)`. The `@Size` constraint fires after Jackson has deserialized the JSON, meaning a 200 MB payload is fully allocated in heap before the constraint violation is raised. The audit's core claim is correct.

The caveat is that Spring Boot configures Tomcat with `server.tomcat.max-http-form-post-size` (2 MB default for form data) and `server.tomcat.max-swallow-size`. For JSON bodies specifically (`POST /api/documents/article` with `Content-Type: application/json`), the effective limit is controlled by Jackson's `StreamReadConstraints` (defaulting to 20 MB per string token in Jackson 2.15+) and by any `spring.servlet.multipart` settings. Neither is the same as the DTO-level `@Size`. The audit's recommendation to add a filter-level body size limit is valid.

---

### Finding 16 (narrative §17) — `CreateInteractionRequest` VIEW-only restriction in controller body, not DTO

**Verdict: CONFIRMED (minor)**

`RecommendationController.java` line 56: `if (request.kind() != InteractionKind.VIEW) { throw new IllegalArgumentException(...); }` — confirmed. `IllegalArgumentException` is caught by `GlobalExceptionHandler.handleIllegalArgument()` and mapped to 400, so the runtime behaviour is correct. The finding is about maintainability/defence-in-depth, not a current security gap. The audit classifies it LOW, which is appropriate.

---

### Finding 17 (narrative §18) — LIKE wildcards not escaped in `titleContains`

**Verdict: CONFIRMED**

`DocumentSpecifications.titleContains()` lines 31–34:

```java
String pattern = "%" + search.toLowerCase() + "%";
return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
```

The pattern is built by string concatenation. `%` and `_` in the `search` string are not escaped before being embedded in the LIKE pattern. The three-argument `CriteriaBuilder.like(Expression, String, char)` overload (which accepts an escape character) is not used.

A `search` value of `%` becomes pattern `%%%`, which matches any string. A `search` value of `_` matches any title of length >= 1. This is a genuine **semantic injection** — not SQL injection, but the attacker can force match-all behavior. The audit's diagnosis and fix are correct. This is a real bug.

---

### Finding 18 (narrative §19) — `DocumentSpecifications.hasType` receives unvalidated string

**Verdict: CONFIRMED, but LOW severity is appropriate**

`hasType()` uses `cb.equal(root.get("type"), type)` — parameterized, no SQL injection. An invalid type simply returns zero rows. This is a consequence of Finding 13 (no format validation on the `type` query param). The finding is accurate but the practical risk is minimal: worst case is a no-op query.

---

### Finding 19 (narrative §20) — `ReadingListController.getReadingLists` and `createReadingList` lack `@PreAuthorize`

**Verdict: PARTIALLY CORRECT, but the severity claim is overstated**

`ReadingListController.java` lines 38–51: Neither `getReadingLists` nor `createReadingList` has `@PreAuthorize`.

However, the audit's claim that `securityUtils.getCurrentUser()` "throws `UsernameNotFoundException` (a Spring Security exception) which is not mapped to 401 in `GlobalExceptionHandler`" is **factually wrong**. `GlobalExceptionHandler.java` line 205–210 explicitly handles `UsernameNotFoundException` and maps it to 401:

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex, ...) {
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

So the claimed "500 instead of 401" failure mode does not exist. An unauthenticated request to `GET /api/reading-lists` hits `SecurityConfig`'s `.anyRequest().authenticated()` rule (line 103), which the `JwtAuthenticationFilter` enforces — the `JsonAuthenticationEntryPoint` returns 401 before the controller is reached. The defense-in-depth gap (no explicit `@PreAuthorize`) is real, but the described failure mode (500 response) is incorrect.

**The finding is partially correct:** the missing `@PreAuthorize` is a genuine defence-in-depth gap, but the specific failure mode described (500 from `UsernameNotFoundException`) is refuted by the `GlobalExceptionHandler`.

---

### Finding 20 (narrative §21) — `UpdateDocumentRequest.categoryIds` null vs. empty semantics undocumented

**Verdict: CONFIRMED**

`DocumentService.update()` lines 107–109:

```java
if (request.categoryIds() != null) {
    reconcileCategories(document, resolveCategories(request.categoryIds()));
}
```

`null` = no change; `[]` = remove all categories. This dual semantic is not documented in the DTO or enforced with a validation constraint. The finding is accurate. The risk level (LOW) is appropriate — it is an API design issue more than a security vulnerability.

---

### Finding 21 (narrative §23) — `RecommendationController.logInteraction` lacks explicit `@PreAuthorize`

**Verdict: CONFIRMED (low severity)**

`RecommendationController.logInteraction()` has no `@PreAuthorize`. `SecurityConfig` line 100: `.requestMatchers(HttpMethod.POST, "/api/documents/*/interactions").authenticated()` provides coverage. The concern is defence-in-depth only. Additionally, `securityUtils.getCurrentUser()` is called on line 59, which would throw (and be mapped to 401) if no user were present. The finding is real but the risk is minimal given dual-layer protection.

---

### Finding 22 (narrative §25) — `LoginRequest.password` missing `@Size(max=…)`

**Verdict: DUPLICATE of Finding 1**

This is the same finding as Finding 1 restricted to the `password` field of `LoginRequest`. The audit report already covers `LoginRequest.password` in narrative §1 and summary table entry 1. The audit lists it again as a separate finding (narrative §25, summary table entry 22). This is not a distinct issue. It is correctly identified but redundantly listed.

---

## Findings the Audit Correctly Self-Refuted

| Narrative Section | Self-Refuted Statement |
|---|---|
| §6 | "`CreateReadingListRequest` and `UpdateReadingListRequest` — name has no `@Size(max=…)` constraint" — The audit immediately corrects itself: both have `@Size(max=255)`. Correctly marked "No actionable finding." |
| §22 | "`CommentController.addComment` — missing `@PreAuthorize`" — The audit correctly notes `@PreAuthorize("isAuthenticated()")` is present on the method. Correctly marked "No actionable finding." |
| §24 | `DocumentController.update` returns 200 with body — the audit correctly notes this is valid REST practice. Correctly marked "No actionable finding." |

---

## Summary

| Finding (summary table) | Verdict | Key reason |
|---|---|---|
| 1 — `LoginRequest` missing `@Size(max=…)` | CONFIRMED | No max size constraint on either field |
| 2 — `RegisterRequest` missing email max / password max | CONFIRMED | No max size on email; password has min only |
| 3 — `UpdateUserRequest.displayName` max 255 vs. registration max 50 | CONFIRMED | Inconsistency confirmed in both DTOs |
| 4 — Empty `UpdateUserRequest` accepted as no-op | CONFIRMED | Both fields optional; `@Valid` present but no "at least one non-null" constraint |
| 5 — `UpdateUserRequest.password` whitespace accepted | REFUTED | `NullOrNotBlankValidator` uses `String.isBlank()` which correctly rejects whitespace-only. No security gap exists. HIGH rating is unjustified. |
| 6 — Free-text `type` field | CONFIRMED | `String type` with no enum validation |
| 7 — `visibility` silently defaults to PUBLIC | CONFIRMED | No `@NotNull`; service applies default |
| 8 — `UpdateDocumentRequest.categoryIds` no size cap | CONFIRMED | No `@Size(max=…)`; N+1 query risk real |
| 9 — MIME type trust client header | CONFIRMED | `getContentType()` is caller-controlled; stored XSS vector exists |
| 10 — Missing `X-Content-Type-Options: nosniff` on file serve | REFUTED | Spring Security's `XContentTypeOptionsHeaderWriter` applies this header to all responses by default; `SecurityConfig` does not disable it |
| 11 — File extension not cross-validated against MIME | CONFIRMED | No extension-to-MIME cross-check; double-extension case works as described |
| 12 — `search` param unbounded; `@Validated` missing | CONFIRMED | No `@Validated` on controller; no length cap; leading `%` wildcard prevents index use |
| 13 — `type` param no length/format constraint | CONFIRMED | No validation; same `@Validated` absence issue |
| 14 — Page number unbounded | CONFIRMED | `PaginationConfig` caps size only; no page number cap in 3 of 4 controllers |
| 15 — Article body buffered before `@Size` fires | CONFIRMED | Jackson deserializes fully before validation; transport-layer limit absent |
| 16 — VIEW-only restriction in controller body | CONFIRMED | Correct but LOW severity; `IllegalArgumentException` correctly mapped to 400 |
| 17 — LIKE wildcards not escaped | CONFIRMED | Real semantic injection bug; `%` and `_` pass through to LIKE pattern unescaped |
| 18 — `hasType` receives unvalidated string | CONFIRMED | Parameterized so no SQL injection; semantic concern is real but low risk |
| 19 — `ReadingListController` missing `@PreAuthorize` | PARTIALLY CORRECT | Missing annotation confirmed; but the "500 instead of 401" failure mode is REFUTED — `GlobalExceptionHandler` explicitly maps `UsernameNotFoundException` to 401 |
| 20 — `categoryIds` null vs empty semantics undocumented | CONFIRMED | Dual semantics in service confirmed |
| 21 — `logInteraction` missing `@PreAuthorize` | CONFIRMED | Low risk given SecurityConfig coverage |
| 22 — `LoginRequest.password` no max | DUPLICATE of finding 1 | Already covered in finding 1 |

---

## Count Summary

| Verdict | Count |
|---|---|
| CONFIRMED | 16 |
| PARTIALLY CORRECT | 1 (Finding 19) |
| REFUTED | 2 (Finding 5, Finding 10) |
| DUPLICATE | 1 (Finding 22 = Finding 1) |

**Total distinct findings assessed: 22 (21 unique + 1 duplicate)**

---

## Most Significant Errors in the Audit

1. **Finding 5 rated HIGH is wrong.** The `NullOrNotBlankValidator` correctly rejects whitespace-only strings via `String.isBlank()`. The audit's own in-line reasoning refutes the claim but then retreats to a non-issue about message ordering. No HIGH severity vulnerability exists here.

2. **Finding 10 is refuted.** Spring Security's default `XContentTypeOptionsHeaderWriter` adds `X-Content-Type-Options: nosniff` to all filter-chain responses. The `SecurityConfig` in this codebase does not disable that default. The header is already present on file-serving responses. The audit treats the Spring Security default as if it did not exist.

3. **Finding 19 failure mode is wrong.** The audit claims an unauthenticated request to `ReadingListController` would produce a 500 because `UsernameNotFoundException` is not handled. It is handled: `GlobalExceptionHandler` maps it to 401. The defence-in-depth gap (missing `@PreAuthorize`) remains a valid minor observation, but the described consequence is incorrect.

4. **Finding 22 is a duplicate** of Finding 1. The audit lists `LoginRequest.password` missing `@Size(max=…)` twice under different section numbers.
