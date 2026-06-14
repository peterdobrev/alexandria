# comments — Endpoint findings

## Endpoints covered
- `GET /api/documents/{documentId}/comments`
- `POST /api/documents/{documentId}/comments`
- `PUT /api/documents/{documentId}/comments` (unsupported method probe)
- `PATCH /api/documents/{documentId}/comments` (unsupported method probe)
- `DELETE /api/documents/{documentId}/comments` (unsupported method probe)
- `HEAD /api/documents/{documentId}/comments`
- `OPTIONS /api/documents/{documentId}/comments`
- `DELETE /api/documents/{documentId}/comments/{commentId}`
- `GET /api/documents/{documentId}/comments/{commentId}` (unsupported method probe)
- `PUT /api/documents/{documentId}/comments/{commentId}` (unsupported method probe)
- `PATCH /api/documents/{documentId}/comments/{commentId}` (unsupported method probe)
- `POST /api/documents/{documentId}/comments/{commentId}` (unsupported method probe)

Document used for tests: `609a8cf7-cf54-4ab6-9edc-3aef8534a928` (PUBLIC).

## Findings

### F-COMMENTS-001: Unsupported HTTP methods return 500 instead of 405 Method Not Allowed
- **Endpoint:** `PUT|PATCH|DELETE /api/documents/{documentId}/comments` and `GET|PUT|PATCH|POST /api/documents/{documentId}/comments/{commentId}`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  COMMENT_ID=21168cda-715a-41b6-8c7b-b64e69f131ea
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":"x"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X PATCH  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":"x"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN"                                       "http://localhost:8080/api/documents/$DOC_ID/comments"
  curl -s -o /dev/null -w '%{http_code}\n' -X GET    -H "Authorization: Bearer $TOKEN"                                       "http://localhost:8080/api/documents/$DOC_ID/comments/$COMMENT_ID"
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "http://localhost:8080/api/documents/$DOC_ID/comments/$COMMENT_ID" -d '{"body":"x"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X PATCH  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "http://localhost:8080/api/documents/$DOC_ID/comments/$COMMENT_ID" -d '{"body":"x"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X POST   -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "http://localhost:8080/api/documents/$DOC_ID/comments/$COMMENT_ID" -d '{"body":"x"}'
  ```
- **Observed:** Every probe returns `HTTP 500` with the generic `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}` body.
- **Expected:** `HTTP 405 Method Not Allowed` with an `Allow` header listing supported methods (Spring's default `HttpRequestMethodNotSupportedException` translates to 405). 500 turns method-mismatch into a server error, hides the real cause, and pollutes logs.
- **Notes:** The global exception handler is catching `HttpRequestMethodNotSupportedException` (and likely siblings such as `NoResourceFoundException` / `HttpMediaTypeNotSupportedException`) in the generic catch-all branch.

### F-COMMENTS-002: Invalid UUID in path returns 500 instead of 400
- **Endpoint:** `GET /api/documents/{documentId}/comments`, `DELETE /api/documents/{documentId}/comments/{commentId}`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/documents/not-a-uuid/comments"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -X DELETE "http://localhost:8080/api/documents/609a8cf7-cf54-4ab6-9edc-3aef8534a928/comments/not-a-uuid"
  ```
- **Observed:** `HTTP 500` with the generic error body for both probes.
- **Expected:** `HTTP 400 Bad Request` (a `MethodArgumentTypeMismatchException` for a `UUID` path variable is a client error). Returning 500 breaks the contract and leaks "server is broken" semantics.

### F-COMMENTS-003: Malformed JSON / empty body returns 500 instead of 400
- **Endpoint:** `POST /api/documents/{documentId}/comments`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  # malformed JSON
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{not json'
  # missing body entirely
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -X POST "http://localhost:8080/api/documents/$DOC_ID/comments"
  ```
- **Observed:** `HTTP 500` with the generic error body.
- **Expected:** `HTTP 400 Bad Request` (Spring's `HttpMessageNotReadableException` / `HttpMessageConversionException` should map to 400). 500 here means a malformed client payload looks like a server bug.

### F-COMMENTS-004: Wrong/missing Content-Type returns 500 instead of 415/400
- **Endpoint:** `POST /api/documents/{documentId}/comments`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain"       -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":"x"}'
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/xml"  -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" -d '<x/>'
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN"                                      -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" --data-binary '{"body":"x"}'
  ```
- **Observed:** `HTTP 500` for all three.
- **Expected:** `HTTP 415 Unsupported Media Type` for `text/plain` / `application/xml`; `HTTP 400` (or 415) for the no-Content-Type case. Spring raises `HttpMediaTypeNotSupportedException` which natively maps to 415; the global handler is masking it as 500.

### F-COMMENTS-005: `sort=` with an unknown column returns 500 instead of 400
- **Endpoint:** `GET /api/documents/{documentId}/comments`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/documents/$DOC_ID/comments?sort=fakeColumn,asc"
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/documents/$DOC_ID/comments?sort=garbage,asc"
  ```
- **Observed:** `HTTP 500` (PropertyReferenceException from Spring Data bubbling out as an unexpected error).
- **Expected:** `HTTP 400 Bad Request` with a clear message. Public, anonymous-readable endpoints should not return 500 for client-supplied query parameters; this is also a denial-of-service amplifier (any anonymous caller can trigger 500s + stack traces in logs).

### F-COMMENTS-006: DELETE non-existent comment returns 403 instead of 404
- **Endpoint:** `DELETE /api/documents/{documentId}/comments/{commentId}`
- **Severity:** Medium
- **Category:** auth
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -X DELETE "http://localhost:8080/api/documents/$DOC_ID/comments/00000000-0000-0000-0000-000000000000"
  ```
- **Observed:** `HTTP 403` with `{"message":"Access denied"}`.
- **Expected:** `HTTP 404 Not Found`. The `@PreAuthorize("@ownership.isCommentOwnerOrAdmin(#commentId, principal)")` runs before the service checks existence, so a missing entity is reported as a permission failure. This is also confusing for legitimate clients (a real owner who reuses an old id receives 403). Either a 404 should be returned uniformly, or the ownership predicate should treat "not found" as a 404 instead of denying.
- **Notes:** Same pattern produces F-COMMENTS-008 (DELETE-then-DELETE 403 instead of 404).

### F-COMMENTS-007: DELETE with mismatched documentId returns 404 instead of 400/404 with consistent shape
- **Endpoint:** `DELETE /api/documents/{documentId}/comments/{commentId}`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  COMMENT_ID=21168cda-715a-41b6-8c7b-b64e69f131ea  # belongs to doc 609a8cf7-...
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" -X DELETE "http://localhost:8080/api/documents/00000000-0000-0000-0000-000000000000/comments/$COMMENT_ID"
  ```
- **Observed:** `HTTP 404` with `{"message":"Comment not found: 21168cda-..."}`.
- **Expected:** Functionally OK that this is rejected, but the message is misleading — the comment exists, only the parent document path is wrong. A 404 mentioning the document, or a 400 indicating the path mismatch, would be clearer. Bigger issue: an authenticated request with a *valid but unrelated* commentId on a *non-existent* document path now reveals the fact the comment id exists in the system (and discloses its id back in the message), which is a minor info-leak through error messages.

### F-COMMENTS-008: DELETE-then-DELETE returns 403 on the second call (not idempotent / not 404)
- **Endpoint:** `DELETE /api/documents/{documentId}/comments/{commentId}`
- **Severity:** Medium
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  # Create a comment owned by current user
  CID=$(curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":"to-delete"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  curl -s -o /dev/null -w 'first:  %{http_code}\n' -H "Authorization: Bearer $TOKEN" -X DELETE "http://localhost:8080/api/documents/$DOC_ID/comments/$CID"
  curl -s -o /dev/null -w 'second: %{http_code}\n' -H "Authorization: Bearer $TOKEN" -X DELETE "http://localhost:8080/api/documents/$DOC_ID/comments/$CID"
  ```
- **Observed:** First call `204`, second call `403 Forbidden` ("Access denied").
- **Expected:** Second call `HTTP 404 Not Found` (RFC 7231 §4.3.5: a DELETE for a target that no longer exists should return 404; idempotent semantics are preserved). Returning 403 misleads clients into thinking they lost permissions.

### F-COMMENTS-009: HEAD on the public comments collection returns 401 instead of 200/no-body
- **Endpoint:** `HEAD /api/documents/{documentId}/comments`
- **Severity:** Medium
- **Category:** method
- **Reproduction:**
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -I "http://localhost:8080/api/documents/609a8cf7-cf54-4ab6-9edc-3aef8534a928/comments"
  ```
- **Observed:** `HTTP 401`.
- **Expected:** `HTTP 200` (no body) — by RFC 9110 §9.3.2, every resource that supports GET must support HEAD with the same authentication requirements. Since `GET` here is anonymous-allowed, `HEAD` should be too. Currently the security filter chain demands authentication for HEAD even though GET is permitted, which breaks proxies, link-checkers, and conditional-request flows.

### F-COMMENTS-010: POST silently coerces wrong JSON types instead of rejecting
- **Endpoint:** `POST /api/documents/{documentId}/comments`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":42}'
  ```
- **Observed:** `HTTP 201` with `{"body":"42",...}` — the integer was silently coerced to the string `"42"`.
- **Expected:** `HTTP 400 Bad Request`. Per the project guideline "Make APIs strict — fail fast rather than being fault-tolerant", non-string `body` should be rejected. Jackson's `MapperFeature.ALLOW_COERCION_OF_SCALARS` should be disabled (or set to `CoercionAction.Fail` for strings).

### F-COMMENTS-011: GET silently ignores invalid pagination parameters instead of rejecting them
- **Endpoint:** `GET /api/documents/{documentId}/comments`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  # negative page: silently treated as 0
  curl -s -o /dev/null -w 'page=-1   -> %{http_code}\n' "http://localhost:8080/api/documents/$DOC_ID/comments?page=-1"
  # non-numeric page: silently treated as 0
  curl -s -o /dev/null -w 'page=abc  -> %{http_code}\n' "http://localhost:8080/api/documents/$DOC_ID/comments?page=abc"
  # huge size: silently capped to 2000
  curl -s "http://localhost:8080/api/documents/$DOC_ID/comments?size=99999" | python3 -c "import json,sys;d=json.load(sys.stdin);print('size cap=',d['size'])"
  ```
- **Observed:** `page=-1` → 200 (page 0), `page=abc` → 200 (page 0), `size=99999` → 200 with `size=2000`.
- **Expected:** `HTTP 400` for invalid values, or at minimum a documented cap. Silently ignoring bad input violates the strict-API guideline and hides client bugs.

### F-COMMENTS-012: Documented response shape for paginated list does not match actual response
- **Endpoint:** `GET /api/documents/{documentId}/comments`
- **Severity:** Medium
- **Category:** error-shape (response contract)
- **Reproduction:**
  ```bash
  curl -s "http://localhost:8080/api/documents/609a8cf7-cf54-4ab6-9edc-3aef8534a928/comments" | python3 -m json.tool
  ```
- **Observed:** Body contains `{ content, empty, first, last, number, numberOfElements, pageable:{ offset, pageNumber, pageSize, paged, sort:{...}, unpaged }, size, sort:{ empty, sorted, unsorted }, totalElements, totalPages }` — i.e. the raw Jackson serialization of `PageImpl`.
- **Expected:** `docs/rest-api.md` documents the shape `{ content, page, size, totalElements, totalPages, last }`. The real payload exposes Spring internals (`pageable`, `sort.empty`, `unpaged`, `numberOfElements`, etc.) that are not part of the documented contract and are warned-against by Spring (`PageImpl` Jackson serialization is unstable). Either update the docs or wrap responses in a project-defined `PagedResponse` DTO consistent with what's already promised.

### F-COMMENTS-013: 201 Created from POST does not return a Location header
- **Endpoint:** `POST /api/documents/{documentId}/comments`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  curl -is -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -X POST "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":"loc test"}' | grep -i '^location:'
  ```
- **Observed:** No `Location` header on the response (the grep returns nothing).
- **Expected:** RFC 9110 §15.3.2 — a `201 Created` SHOULD include a `Location` header pointing to the new resource. Even though the API has no `GET /comments/{id}` (so the URL would only be useful for `DELETE`), the controller should set `Location: /api/documents/{documentId}/comments/{newCommentId}` for consistency with the rest of the API contract (and the OpenAPI spec).

### F-COMMENTS-014: Auth failures return empty body; 401/403 error shape inconsistent with other errors
- **Endpoint:** `POST /api/documents/{documentId}/comments`, `DELETE /api/documents/{documentId}/comments/{commentId}`
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:**
  ```bash
  DOC_ID=609a8cf7-cf54-4ab6-9edc-3aef8534a928
  COMMENT_ID=21168cda-715a-41b6-8c7b-b64e69f131ea
  # No token
  curl -is -X POST -H "Content-Type: application/json" "http://localhost:8080/api/documents/$DOC_ID/comments" -d '{"body":"x"}' | tail -5
  # Garbage token
  curl -is -H "Authorization: Bearer garbage" -X DELETE "http://localhost:8080/api/documents/$DOC_ID/comments/$COMMENT_ID" | tail -5
  ```
- **Observed:** Status `401` with **empty body**.
- **Expected:** A consistent JSON error envelope (the same `{status,error,message,timestamp,path}` returned by 400/403/404/500 elsewhere, or RFC 7807 problem+json). Clients must currently special-case "no body" for auth failures, which contradicts every other error path on this endpoint set.

### F-COMMENTS-015: Error response shape is project-bespoke, not RFC 7807
- **Endpoint:** `GET|POST /api/documents/{documentId}/comments`, `DELETE /api/documents/{documentId}/comments/{commentId}` (all error paths that do return a body)
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s "http://localhost:8080/api/documents/00000000-0000-0000-0000-000000000000/comments" | python3 -m json.tool
  ```
- **Observed:** `{"status":404,"error":"Not Found","message":"Document not found: ...","timestamp":"...","path":"/api/documents/.../comments"}` with `Content-Type: application/json`.
- **Expected:** The project-internal shape is fine and consistent across 400/403/404/500, but it is *not* `application/problem+json` and does not match RFC 7807 (no `type`, `title`, `detail`, `instance`). If the project intends the "standard" error envelope going forward, `Content-Type: application/problem+json` and the standard fields would be more interoperable. Flagging because (a) the spec doesn't document the shape at all, and (b) the *empty* 401/403 bodies (F-COMMENTS-014) make the inconsistency starker.

## Endpoints with no findings
- `GET /api/documents/{documentId}/comments` — happy path returns 200 with the documented `content[]` items (`id`, `author{id,displayName}`, `body`, `createdAt`); anonymous access is correctly allowed for PUBLIC documents. (Wrapper shape and query-param handling are flagged separately above.)
- `POST /api/documents/{documentId}/comments` — happy path returns 201 with a `CommentResponse` body matching the docs; `@NotBlank` and `@Size(1,2000)` are correctly enforced (400 with sensible messages for missing/blank/oversize); 401 returned (with empty body — see F-COMMENTS-014) for unauthenticated calls; 404 returned for non-existent `documentId`.
- `DELETE /api/documents/{documentId}/comments/{commentId}` — happy path returns 204 No Content; 403 correctly returned when a different authenticated user attempts to delete; 401 correctly returned when unauthenticated.

---

## Verification pass

| Finding ID | Status | Note |
|---|---|---|
| F-COMMENTS-001 | CONFIRMED | All 7 method probes returned 500 instead of 405 |
| F-COMMENTS-002 | CONFIRMED | Both invalid UUID probes returned 500 instead of 400 |
| F-COMMENTS-003 | CONFIRMED | Malformed JSON and empty body both returned 500 instead of 400 |
| F-COMMENTS-004 | CONFIRMED | text/plain, application/xml, and missing Content-Type all returned 500 instead of 415/400 |
| F-COMMENTS-005 | CONFIRMED | Both `sort=fakeColumn,asc` and `sort=garbage,asc` returned 500 instead of 400 |
| F-COMMENTS-006 | CONFIRMED | DELETE on a non-existent comment id returned 403 "Access denied" instead of 404 |
| F-COMMENTS-007 | CONFIRMED | Reproduced with a freshly created comment: mismatched documentId path returns 404 with "Comment not found: <id>" — confirms the info-leak via error message |
| F-COMMENTS-008 | CONFIRMED | First DELETE 204, second DELETE 403 (not 404) |
| F-COMMENTS-009 | CONFIRMED | HEAD on the public comments collection returned 401 |
| F-COMMENTS-010 | CONFIRMED | `{"body":42}` returned 201 with `body:"42"` (silent scalar coercion) |
| F-COMMENTS-011 | CONFIRMED | `page=-1` 200, `page=abc` 200, `size=99999` 200 with `size=2000` cap |
| F-COMMENTS-012 | CONFIRMED | Body keys include `pageable`, `sort`, `empty`, `numberOfElements`, etc.; docs only promise `content,page,size,totalElements,totalPages,last` |
| F-COMMENTS-013 | CONFIRMED | No `Location:` header on 201 response |
| F-COMMENTS-014 | CONFIRMED | Both no-token and garbage-token returned 401 with `Content-Length: 0` (empty body) |
| F-COMMENTS-015 | CONFIRMED | Body shape is `{status,error,message,timestamp,path}` with `Content-Type: application/json`, not RFC 7807 `application/problem+json` |

### Additional findings from verification
- F-COMMENTS-016 (Medium, validation/error-shape): `Accept: garbage` on `GET /api/documents/{documentId}/comments` returns **HTTP 500** instead of 406 Not Acceptable / 400 Bad Request. A malformed Accept header is a client error and Spring's `InvalidMediaTypeException` is being swallowed by the catch-all handler. Repro: `curl -i -H "Accept: garbage" http://localhost:8080/api/documents/$DOC_ID/comments` → 500. (Same root cause as F-COMMENTS-001/004 — global exception handler missing more Spring-MVC content-negotiation exceptions.)
- F-COMMENTS-017 (Medium, auth/method): `OPTIONS /api/documents/{documentId}/comments` and `Accept: application/xml` / `Accept: text/html` (which would normally produce 406) all return **HTTP 401** to anonymous callers, even though `GET` is anonymous-allowed. Same misconfiguration family as F-COMMENTS-009 (HEAD): the security filter chain demands authentication for non-GET verbs and content-negotiation 4xx paths even when the resource itself is public. Breaks CORS preflight from browsers that don't carry credentials.
- F-COMMENTS-018 (Low, validation/strict-API): `POST` accepts and silently drops unknown fields. `{"body":"valid","author":"hacker","id":"injected","extra":"field"}` returns 201 with the server-assigned `id` and `author`, but unknown fields are not echoed back and not rejected. Per the project's "Make APIs strict — fail fast" guideline, Jackson should be configured with `FAIL_ON_UNKNOWN_PROPERTIES=true` so a client sending the wrong shape gets a 400 instead of a silent acceptance that may hide bugs (typoed field names, deprecated clients, attempts at mass-assignment).

### Refuted findings — corrected understanding
- none — every original finding reproduced as described.
