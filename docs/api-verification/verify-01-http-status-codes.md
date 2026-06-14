# Adversarial Verification — HTTP Status Code Audit (01-http-status-codes.md)

Verified on: 2026-06-14  
Reviewer role: skeptic — every finding is verified independently against the actual source.

---

## Methodology

For each finding in the audit report, I read the exact lines cited and compared them to the
claim made. Verdicts are:

- **CONFIRMED** — the code is exactly as described and the problem is real.
- **REFUTED** — the code is correct; the finding is wrong.
- **PARTIALLY-CORRECT** — the factual code observation is accurate but the severity,
  framing, or conclusion is wrong or overstated.

The audit report uses two numbering schemes: the summary table (rows 1–12) and the detailed
findings sections (Finding 1 – Finding 13, where Finding 9 and Finding 13 self-declare "no
issue"). I verify the detailed findings by number and note where summary-table rows duplicate
a finding.

---

## Finding 1 — POST `/documents/{id}/interactions` returns 204 instead of 201

**Claim:** `RecommendationController.java` lines 52–61 annotates the POST endpoint with
`@ResponseStatus(HttpStatus.NO_CONTENT)` and should return 201.

**Verification:** The file reads (lines 52–61):

```java
@ResponseStatus(HttpStatus.NO_CONTENT)
@PostMapping("/documents/{id}/interactions")
public void logInteraction(@PathVariable("id") UUID documentId,
                           @Valid @RequestBody CreateInteractionRequest request) {
    if (request.kind() != InteractionKind.VIEW) {
        throw new IllegalArgumentException("Only VIEW interactions can be posted by clients");
    }
    User currentUser = securityUtils.getCurrentUser();
    interactionService.logView(currentUser, documentId);
}
```

The annotation is exactly `HttpStatus.NO_CONTENT`. The endpoint creates a server-side
interaction record. This matches the claim precisely.

**However, the severity assessment needs scrutiny.** The audit marks this HIGH on the basis
that RFC 9110 requires 201 for resource-creating POSTs. That is a reasonable interpretation,
but the HTTP specification does not mandate 201 for every POST that has a side effect — 204
is permitted when there is no response body and no Location header is meaningful. Whether
this is a real bug depends on the API contract. The code is unambiguously returning 204, and
the audit's description of what it does is accurate.

**Verdict: CONFIRMED** — the endpoint returns 204. Whether 201 is strictly required is
debatable, but the finding correctly identifies the behaviour and the discrepancy with REST
conventions. The HIGH severity is debatable (many interaction-logging endpoints intentionally
return 204) but the factual observation is correct.

---

## Finding 2 — Duplicate of Finding 1 (summary table row 2)

**Claim:** Summary table row 2 re-states the same 204 vs 201 issue on the same lines
(52–61) as row 1.

**Verdict: DUPLICATE** — this is the same code as Finding 1, stated twice in the summary
table under different severity labels. No distinct code observation is being made.

---

## Finding 3 / Detailed Finding 2 — `DocumentController.update` returns raw type, status invisible

**Claim:** `DocumentController.java` lines 100–105 returns `DocumentDetail` directly with no
`ResponseEntity` or `@ResponseStatus`. Spring defaults to 200. The finding calls this
"inconsistent" with other endpoints.

**Verification:** Lines 100–105:

```java
@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
@PutMapping("/{id}")
public DocumentDetail update(@PathVariable UUID id,
                             @Valid @RequestBody UpdateDocumentRequest request) {
    return documentService.update(id, request);
}
```

The method does return a raw type. Spring does default to 200. The claim is factually
accurate. However, the report itself acknowledges "200 OK is correct for a PUT that returns
the updated resource." The actual HTTP behaviour is not wrong — 200 is the right status for
this operation.

**The inconsistency claim is partially inaccurate.** Looking at the other controllers:
- `CategoryController.update` at line 50 returns `ResponseEntity.ok(...)` — uses
  `ResponseEntity`.
- `CommentController` has no update method.
- `ReadingListController.updateReadingList` line 61 returns `ResponseEntity.ok(...)`.

So there is some inconsistency within the project. However, the audit's framing of this as a
MEDIUM severity finding conflates a style concern with a status-code bug. The status code
returned (200) is semantically correct. This is purely a code-style inconsistency.

The audit also misnumbers its own findings: the summary table calls this finding 3/4/5 but
the detailed section labels it "Finding 2" and "Finding 3". The underlying observation about
lines 100–105 is accurate.

**Verdict: PARTIALLY-CORRECT** — the raw return type is real and the inconsistency exists,
but 200 is the correct status and this is not a status-code bug. The MEDIUM severity
overstates a style issue as a correctness problem.

---

## Finding 4 / Detailed Finding 3 — `DocumentController.list` and `get` return raw types

**Claim:** `DocumentController.java` lines 52–72 return raw objects with no `ResponseEntity`
or `@ResponseStatus`. Said to be inconsistent with "all other read endpoints in other
controllers" which "use `ResponseEntity.ok(...)`".

**Verification:** Lines 52–72 confirmed:

```java
@GetMapping
public PageResponse<DocumentSummary> list(...) { ... }

@GetMapping("/{id}")
public DocumentDetail get(@PathVariable UUID id, ...) { ... }
```

Both methods return raw types. Spring defaults to 200 OK. The audit claims "all other read
endpoints in other controllers use `ResponseEntity.ok(...)`." Let me check this claim:

- `CategoryController.list` (line 33): `return ResponseEntity.ok(...)` — uses ResponseEntity.
- `CommentController.getComments` (line 36): `return ResponseEntity.ok(...)` — uses ResponseEntity.
- `ReadingListController.getReadingLists` (line 39): `return ResponseEntity.ok(...)` — uses ResponseEntity.
- `UserController.getCurrentUser` (line 29): `return ResponseEntity.ok(...)` — uses ResponseEntity.
- `UserController.getUser` (line 34): `return ResponseEntity.ok(...)` — uses ResponseEntity.
- `RecommendationController.getRecommendations` (line 39): `return ResponseEntity<...>` — uses ResponseEntity.

The claim that all other read endpoints use `ResponseEntity.ok(...)` is **accurate**.
`DocumentController.list` and `DocumentController.get` are the only two outliers among all
GET endpoints in the codebase.

The 200 status itself is correct. This is a style/consistency issue. The finding is factually
accurate about the inconsistency, but calling it a status-code issue is a stretch.

**Verdict: PARTIALLY-CORRECT** — the inconsistency is real and correctly identified. The
status code (200) is not wrong. MEDIUM severity for a pure style inconsistency is arguably
overstated.

---

## Finding 6 / Detailed Finding 4 — `UsernameNotFoundException` mapped to 401 (HIGH)

**Claim:** `GlobalExceptionHandler.java` lines 205–210 maps `UsernameNotFoundException` to
401 Unauthorized. The finding argues (a) this is semantically wrong (should be 404 for a
missing user resource), and (b) the handler is dead code for the JWT filter path.

**Verification of (a) — semantic correctness:**

`UsernameNotFoundException` is a Spring Security exception thrown exclusively by
`UserDetailsService.loadUserByUsername` when the user cannot be found. In this codebase the
only call site is `JwtAuthenticationFilter.setAuthentication` (line 71):

```java
UserDetails userDetails = userDetailsService.loadUserByUsername(email);
```

This is called during JWT validation — it is an authentication context, not a resource
lookup. Mapping this to 401 is semantically reasonable: if a valid JWT names a user that no
longer exists, authentication has failed. The audit's argument that it should be 404 ("a
missing user resource should be 404") conflates the authentication context with a resource
fetch. When the JWT filter calls `loadUserByUsername`, it is not a client asking "does this
user exist?" — it is validating an authentication claim. 401 is the more appropriate response
here.

**Verification of (b) — dead code claim:**

The JWT filter (lines 49–55) catches `UsernameNotFoundException` in its own try/catch and
calls `entryPoint.commence(...)`, writing the response directly and returning. This means the
exception never propagates to Spring MVC's dispatcher and never reaches
`GlobalExceptionHandler`. The handler at lines 205–210 is indeed unreachable from the JWT
filter path.

The dead code claim is **confirmed**. The `handleUsernameNotFound` method can never be
invoked from the only current call site of `loadUserByUsername`. The semantic-correctness
argument (401 vs 404) is weaker than presented — 401 is defensible — but the dead code
observation is accurate.

**Verdict: PARTIALLY-CORRECT** — the dead code observation (Finding 10 / summary row 4b) is
confirmed. The semantic argument that 401 is "wrong" and 404 is "correct" is debatable and
overstated; 401 is a reasonable mapping for a failed authentication lookup. The HIGH
severity for the 401-vs-404 semantic argument alone is overstated. The dead code aspect is
a genuine issue.

---

## Finding 7 / Detailed Finding 10 — `UsernameNotFoundException` handler is dead code

This is the same code observation as Finding 4's part (b), presented again as a separate
numbered finding in the detailed section. The dead code claim is independently confirmed
above.

**Verdict: CONFIRMED (duplicate of part of Finding 4/6)** — the `@ExceptionHandler(UsernameNotFoundException.class)` handler in `GlobalExceptionHandler` is unreachable from the only current call site (`JwtAuthenticationFilter` catches it first).

---

## Finding 8 / Detailed Finding 5 — `InvalidDocumentContentException` message silently discarded (HIGH)

**Claim:** `InvalidDocumentContentException` extends `IllegalArgumentException`. When thrown,
`handleIllegalArgument` catches it and returns the hard-coded string `"Invalid request"`,
discarding the specific message.

**Verification:**

`InvalidDocumentContentException.java`:
```java
public class InvalidDocumentContentException extends IllegalArgumentException {
    public InvalidDocumentContentException(String message) {
        super(message);
    }
}
```

`GlobalExceptionHandler.java` lines 184–189:
```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                           HttpServletRequest request) {
    log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
}
```

`InvalidDocumentContentException` extends `IllegalArgumentException` and Spring's exception
handler resolution picks the most specific registered handler. There is **no**
`@ExceptionHandler(InvalidDocumentContentException.class)` in `GlobalExceptionHandler`.
Therefore, when `InvalidDocumentContentException` is thrown, it is caught by
`handleIllegalArgument` and the message is replaced with `"Invalid request"`.

The message is logged (`log.warn` includes `ex.getMessage()`) but not returned to the
caller. The claim is accurate.

**Verdict: CONFIRMED** — `InvalidDocumentContentException` is caught by `handleIllegalArgument` and its specific message is not returned to the API caller. The HIGH severity is justified because callers receive no diagnostic information about what was wrong with the document content.

---

## Finding 9 / Detailed Finding 6 — `handleIllegalArgument` discards sort-field validation message (MEDIUM)

**Claim:** `DocumentController.validateSort` (line 140) throws
`IllegalArgumentException("Sort field not allowed: " + order.getProperty())`. The handler
returns `"Invalid request"` instead.

**Verification:** Lines 134–143 of `DocumentController.java`:

```java
private void validateSort(Sort sort) {
    if (sort == null || sort.isUnsorted()) {
        return;
    }
    for (Sort.Order order : sort) {
        if (!ALLOWED_SORT.contains(order.getProperty())) {
            throw new IllegalArgumentException("Sort field not allowed: " + order.getProperty());
        }
    }
}
```

The exception carries a specific, actionable message. `GlobalExceptionHandler` line 188
returns `build(HttpStatus.BAD_REQUEST, "Invalid request", request)` — the message is not
forwarded. The claim is accurate.

**Note:** There is a subtlety the audit misses. Spring Data's `Pageable` resolution from
request parameters may itself throw a `PropertyReferenceException` for invalid sort
properties before `validateSort` is even called. `GlobalExceptionHandler` has a dedicated
handler for `PropertyReferenceException` (lines 84–90) that *does* return a useful message:
`"Invalid sort property: " + ex.getPropertyName()`. So if the sort field is an unknown JPA
property, the caller actually *does* get a meaningful message from the
`PropertyReferenceException` handler. The `validateSort` method's `IllegalArgumentException`
only fires for fields that are valid JPA properties but not in the `ALLOWED_SORT` whitelist.
This is a narrower scenario than the audit implies, though the message-discard problem is
still real for that case.

**Verdict: CONFIRMED** — the message discard is real for the whitelist-violation path. The
MEDIUM severity is appropriate. The audit overstates the practical impact slightly by not
noting the `PropertyReferenceException` fallback for other invalid sort fields.

---

## Finding 11 / Detailed Finding 7 — Pagination bounds message discarded (MEDIUM)

**Claim:** `RecommendationController.getRecommendations` lines 40–47 throws
`IllegalArgumentException` with specific messages for page size and page number violations,
which are discarded by `handleIllegalArgument`.

**Verification:** Lines 40–47:

```java
if (pageable.getPageSize() > MAX_PAGE_SIZE) {
    throw new IllegalArgumentException(
            "Recommendations page size exceeds the maximum of " + MAX_PAGE_SIZE);
}
if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
    throw new IllegalArgumentException(
            "Recommendations page number exceeds the maximum of " + MAX_PAGE_NUMBER);
}
```

Both throw `IllegalArgumentException` with detailed messages. `handleIllegalArgument`
returns `"Invalid request"`. The messages are not forwarded to the caller. The claim is
accurate.

**Verdict: CONFIRMED** — specific pagination-limit messages are discarded. MEDIUM severity is appropriate.

---

## Finding 10 / Detailed Finding 8 — `OwnershipService` throws `NotFoundException` inside `@PreAuthorize`, causing 403 instead of 404 (HIGH)

**Claim:** `isDocumentOwner`, `isReadingListOwner`, and `isCommentOwnerOrAdmin` in
`OwnershipService` throw `DocumentNotFoundException` / `ReadingListNotFoundException` /
`CommentNotFoundException` from within `@PreAuthorize` expressions. Spring Security wraps
these in `AccessDeniedException`, causing 403 instead of 404 for non-existent resources.

**Verification of the OwnershipService code:**

```java
Document document = documentRepository.findById(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));  // line 33

ReadingList list = readingListRepository.findById(listId)
        .orElseThrow(() -> new ReadingListNotFoundException(listId));   // line 52

Comment comment = commentRepository.findById(commentId)
        .orElseThrow(() -> new CommentNotFoundException(commentId));    // line 67
```

All three throw `NotFoundException` subclasses when the resource is missing.

**Verification of the Spring Security behaviour claim:**

Spring Security's method security (`@PreAuthorize`) infrastructure catches exceptions thrown
during SpEL expression evaluation. Specifically, `MethodSecurityInterceptor` (and its
successor `AuthorizationManagerBeforeMethodInterceptor` in Spring Security 6) does **not**
universally wrap all exceptions in `AccessDeniedException`. The behaviour depends on the
Spring Security version and configuration:

- In Spring Security 5 with the legacy `GlobalMethodSecurityConfiguration`, exceptions from
  `@PreAuthorize` beans that are not `AccessDeniedException` or `AuthenticationException`
  propagate as-is (unwrapped) to the servlet container.
- In Spring Security 6 with `@EnableMethodSecurity`, the `AuthorizationManager`-based
  approach similarly lets non-security exceptions propagate naturally.

The audit's assertion that "Spring Security wraps all exceptions from `@PreAuthorize`
evaluations into an `AccessDeniedException`" is **not universally correct** for Spring
Security 6. When `isDocumentOwner` throws `DocumentNotFoundException` (which extends
`RuntimeException` via `NotFoundException`), Spring Security 6's
`AuthorizationManagerBeforeMethodInterceptor` does not catch and re-wrap it — the exception
propagates to the `GlobalExceptionHandler`, which handles `NotFoundException` at lines
134–139 and returns 404.

**However**, the behaviour is version-dependent and configuration-dependent. The audit's
claim is a known gotcha with older Spring Security configurations. Without knowing the exact
Spring Security version and `@EnableMethodSecurity` vs `@EnableGlobalMethodSecurity`
configuration used in this project, this cannot be dismissed entirely.

**Verdict: PARTIALLY-CORRECT** — the code does throw `NotFoundException` subclasses from
within `@PreAuthorize` bean methods, which is a fragile pattern. However, the audit's
assertion that Spring Security **always** wraps these in `AccessDeniedException` is
inaccurate for Spring Security 6's default configuration. In Spring Security 6 with
`@EnableMethodSecurity`, these exceptions typically propagate to `GlobalExceptionHandler` and
return 404 as expected. The finding identifies a real fragility but presents an incorrect
blanket statement about Spring Security behaviour. The HIGH severity is overstated unless the
project can be confirmed to use the legacy configuration that causes wrapping.

---

## Finding 11 / Detailed Finding 11 — `handleInvalidToken` is dead code for JWT filter path (MEDIUM)

**Claim:** `JwtAuthenticationFilter` catches `InvalidTokenException` in its own try/catch
(lines 49–55), so `GlobalExceptionHandler.handleInvalidToken` (lines 198–203) is dead code
for that path.

**Verification:**

The filter catch block (line 49):
```java
} catch (InvalidTokenException | IllegalArgumentException | UsernameNotFoundException e) {
```

And `handleInvalidToken` (lines 198–203):
```java
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex, ...) {
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}
```

The claim is factually correct: the filter catches `InvalidTokenException` before it can
reach the dispatcher. The audit also correctly notes that if `JwtService` were called from
outside the filter, this handler provides a fallback.

**However, the audit labels this MEDIUM severity and calls it a status-code issue.** The
handler returns 401, which is the correct status for an invalid token. The dead code aspect
is a maintenance concern, not a status-code bug. It is not a status-code finding.

**Verdict: CONFIRMED** as a dead-code observation, but **REFUTED** as a status-code finding.
The handler, if it were reachable, returns the correct 401 status. This should not appear in
an HTTP status code audit.

---

## Finding 12 / Detailed Finding 12 — `streamFile` returns 404 when document has no file (MEDIUM)

**Claim:** `DocumentService.streamFile` (lines 179–181) throws `DocumentNotFoundException`
when `document.getUploadedFilePath() == null`, meaning a document that exists returns 404,
misleading callers into thinking the document itself does not exist.

**Verification:** Lines 179–181 of `DocumentService.java`:

```java
if (document.getUploadedFilePath() == null) {
    throw new DocumentNotFoundException(id);
}
```

The document was found at line 173–174, so it exists. Yet when the file path is null, the
same `DocumentNotFoundException` is thrown as if the document did not exist. The
`GlobalExceptionHandler` at lines 134–139 maps `NotFoundException` to 404 with
`ex.getMessage()`. Both the 404 status and the message ("Document not found" or similar)
will be misleading because the document does exist.

The claim is accurate. The 404 status is technically defensible (treating the file as a
sub-resource that doesn't exist), but using `DocumentNotFoundException` (as opposed to a
dedicated `DocumentFileNotFoundException`) makes the response message actively misleading
since the document was found.

**Verdict: CONFIRMED** — the code throws `DocumentNotFoundException` for a document that
exists, causing an ambiguous 404 that conflates "document missing" with "document has no
file". MEDIUM severity is appropriate.

---

## Finding 12 in summary table row 12 — `POST /login` returns 200 OK (LOW)

**Claim:** `AuthController.java` lines 35–38 returns 200 for `POST /login`. The audit notes
200 is acceptable but some APIs use 201.

**Verification:** Lines 35–38:

```java
@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
}
```

200 is returned. The audit itself admits "200 is acceptable" and labels this a "design
opinion finding, not a clear bug." Login endpoints do not create a persistent resource — they
produce a token. 200 is the conventional and correct status for this operation. 201 would be
semantically incorrect because nothing is being created server-side. The audit is
self-contradictory on this point.

**Verdict: REFUTED** — 200 is the correct status for a login endpoint that returns a token.
201 Created would be semantically wrong here (no resource is being created). This finding
should not be in the report.

---

## Finding 13 / Detailed Finding 9 — No separate handler for `AccessForbiddenException` (declared "no issue")

**Claim (self-declared no issue):** `AccessForbiddenException` extends `ForbiddenException`,
which is caught by `handleForbidden`, returning 403. The audit declares this is not a bug.

**Verification:**

`AccessForbiddenException` extends `ForbiddenException`. `handleForbidden` catches
`ForbiddenException.class`, which includes subclasses. `handleForbidden` returns 403 with
`ex.getMessage()`. This is correct.

**Verdict: CONFIRMED as "no issue"** — the audit correctly identifies this as working as
intended. No finding here.

---

## Finding 14 / Detailed Finding 13 — No `@ResponseStatus` on exception classes (declared "no issue")

**Claim (self-declared no issue):** None of the exception classes carry `@ResponseStatus`.
All status mappings are in `GlobalExceptionHandler`. The audit calls this an architectural
choice, not a bug.

**Verification:** All exception files checked confirm no `@ResponseStatus` annotation on any
of them. The centralised handler approach is confirmed. The audit's assessment is correct.

**Verdict: CONFIRMED as "no issue"** — centralising status mappings in `GlobalExceptionHandler` is the correct approach. No finding here.

---

## Cross-cutting claim: "all other read endpoints use `ResponseEntity.ok(...)`"

The audit states in Finding 3 that "all other read endpoints in other controllers use
`ResponseEntity.ok(...)`". Verification confirms this is accurate:

- `CategoryController.list` — `ResponseEntity.ok(...)`
- `CommentController.getComments` — `ResponseEntity.ok(...)`
- `ReadingListController.getReadingLists` — `ResponseEntity.ok(...)`
- `UserController.getCurrentUser` — `ResponseEntity.ok(...)`
- `UserController.getUser` — `ResponseEntity.ok(...)`
- `RecommendationController.getRecommendations` — `ResponseEntity<...>`

`DocumentController.list` and `DocumentController.get` are the only outliers. The claim is
accurate.

---

## Summary

| Detailed Finding | Summary Table Row(s) | Verdict | Notes |
|-----------------|----------------------|---------|-------|
| Finding 1 — POST interactions returns 204 | Row 1, Row 2 | CONFIRMED | Row 2 is a duplicate of Row 1 |
| Finding 2 — `update` raw return type | Row 3 | PARTIALLY-CORRECT | Status (200) is correct; style inconsistency only |
| Finding 3 — `list` / `get` raw return types | Row 4, Row 5 | PARTIALLY-CORRECT | Status (200) is correct; inconsistency accurately identified |
| Finding 4 — `UsernameNotFoundException` → 401 | Row 6 | PARTIALLY-CORRECT | Dead-code half confirmed; 401-vs-404 argument overstated |
| Finding 5 — `InvalidDocumentContentException` message discarded | Row 8 | CONFIRMED | |
| Finding 6 — Sort validation message discarded | Row 9 | CONFIRMED | Audit overstates scope; `PropertyReferenceException` path gives useful messages |
| Finding 7 — Pagination bounds message discarded | Row 11 | CONFIRMED | |
| Finding 8 — `OwnershipService` 403 vs 404 | Row 10 | PARTIALLY-CORRECT | Spring Security 6 does not universally wrap; fragility is real but blanket assertion is wrong |
| Finding 9 — `AccessForbiddenException` (self-declared no issue) | — | CONFIRMED as no-issue | |
| Finding 10 — `UsernameNotFoundException` handler dead code | Row 7 | CONFIRMED | Duplicate of Finding 4's dead-code half |
| Finding 11 — `handleInvalidToken` dead code | — | CONFIRMED (dead code) / REFUTED (as status-code bug) | Status if reachable is correct |
| Finding 12 — `streamFile` 404 for missing file | — | CONFIRMED | |
| Finding 13 — No `@ResponseStatus` on exceptions (self-declared no issue) | — | CONFIRMED as no-issue | |
| Summary Row 12 — `POST /login` returns 200 | Row 12 | REFUTED | 200 is the correct status for login |

### Counts

| Verdict | Count |
|---------|-------|
| CONFIRMED | 6 |
| REFUTED | 1 |
| PARTIALLY-CORRECT | 4 |
| CONFIRMED as no-issue (self-declared) | 2 |
| DUPLICATE (no independent finding) | 1 |

(Counting distinct substantive findings, not summary-table rows.)

---

## Most significant discrepancies with the audit

1. **Finding 8 (OwnershipService 403 vs 404) is overstated.** The audit asserts as fact that
   Spring Security wraps `NotFoundException` in `AccessDeniedException`. In Spring Security 6
   with `@EnableMethodSecurity` (the default), exceptions from `@PreAuthorize` bean methods
   that are not security exceptions propagate naturally to the dispatcher and reach
   `GlobalExceptionHandler`. The finding identifies a real fragility and pattern risk, but the
   claim that "403 is returned instead of 404" may simply not be true in this project's actual
   runtime configuration. This is the most consequential overstatement in the report.

2. **Summary row 12 (POST /login → 200) should not be in this audit.** 200 is the
   conventional and semantically correct status for a login endpoint that returns a token.
   201 would be wrong. The audit contradicts itself by admitting "200 is acceptable" and
   then still including it as a finding.

3. **Findings 4 and 10 overlap substantially.** The dead-code observation about
   `handleUsernameNotFound` is stated twice (once as part of Finding 4 and once as a
   standalone Finding 10). This inflates the apparent finding count.

4. **The 401-vs-404 argument for `UsernameNotFoundException` (Finding 4) is wrong.** When
   `loadUserByUsername` is called from `JwtAuthenticationFilter`, it is part of authentication
   processing, not a resource fetch. 401 is the more semantically appropriate response for a
   JWT that names a non-existent user. The audit's "missing user resource should be 404"
   reasoning applies to a REST resource-fetch context, not an authentication-filter context.
