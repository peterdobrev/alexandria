# reading-lists — Endpoint findings

## Endpoints covered
- `GET /api/reading-lists`
- `POST /api/reading-lists`
- `PUT /api/reading-lists` (method-not-allowed probe)
- `PATCH /api/reading-lists` (method-not-allowed probe)
- `DELETE /api/reading-lists` (method-not-allowed probe)
- `OPTIONS /api/reading-lists` (sanity check)
- `GET /api/reading-lists/{id}`
- `PUT /api/reading-lists/{id}`
- `DELETE /api/reading-lists/{id}`
- `POST /api/reading-lists/{id}` (method-not-allowed probe)
- `PATCH /api/reading-lists/{id}` (method-not-allowed probe)
- `POST /api/reading-lists/{id}/items`
- `GET /api/reading-lists/{id}/items` (method-not-allowed probe)
- `PUT /api/reading-lists/{id}/items` (method-not-allowed probe)
- `DELETE /api/reading-lists/{id}/items` (method-not-allowed probe)
- `DELETE /api/reading-lists/{id}/items/{documentId}`
- `GET /api/reading-lists/{id}/items/{documentId}` (method-not-allowed probe)
- `PUT /api/reading-lists/{id}/items/{documentId}` (method-not-allowed probe)

## Findings

### F-READING-LISTS-001: Unsupported HTTP methods return 500 instead of 405
- **Endpoint:** `PATCH /api/reading-lists`, `PUT /api/reading-lists`, `DELETE /api/reading-lists`, `POST /api/reading-lists/{id}`, `PATCH /api/reading-lists/{id}`, `GET /api/reading-lists/{id}/items`, `PUT /api/reading-lists/{id}/items`, `DELETE /api/reading-lists/{id}/items`, `GET /api/reading-lists/{id}/items/{documentId}`, `PUT /api/reading-lists/{id}/items/{documentId}`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X PATCH -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' http://localhost:8080/api/reading-lists
  curl -s -i -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' http://localhost:8080/api/reading-lists/4a4c21ab-1c75-4573-b8fa-1de936d5fd14
  curl -s -i -X GET -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/4a4c21ab-1c75-4573-b8fa-1de936d5fd14/items
  ```
- **Observed:** HTTP 500, body `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}`. The `Allow` header is also missing on the response.
- **Expected:** HTTP 405 Method Not Allowed with an `Allow` header listing the supported methods. Spring already knows the right methods — `OPTIONS /api/reading-lists` correctly returns `Allow: GET,HEAD,POST,OPTIONS`. The global exception handler is swallowing `HttpRequestMethodNotSupportedException` and converting it into a generic 500.
- **Notes:** This is a systemic flaw of the project-wide exception handler, not a per-endpoint issue, but it manifests on every reading-lists path.

### F-READING-LISTS-002: Malformed JSON / empty body / wrong Content-Type returns 500 instead of 400/415
- **Endpoint:** `POST /api/reading-lists`, `PUT /api/reading-lists/{id}`, `POST /api/reading-lists/{id}/items`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  # empty body
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" http://localhost:8080/api/reading-lists
  # malformed JSON
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":' http://localhost:8080/api/reading-lists
  # text/plain on JSON endpoint
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" -d '{"name":"x"}' http://localhost:8080/api/reading-lists
  # missing Content-Type
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" --data '{"name":"x"}' http://localhost:8080/api/reading-lists
  # application/xml
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/xml" -d "<x/>" http://localhost:8080/api/reading-lists/4a4c21ab-1c75-4573-b8fa-1de936d5fd14/items
  # PUT empty body
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" http://localhost:8080/api/reading-lists/4a4c21ab-1c75-4573-b8fa-1de936d5fd14
  # POST items empty body
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" http://localhost:8080/api/reading-lists/4a4c21ab-1c75-4573-b8fa-1de936d5fd14/items
  ```
- **Observed:** HTTP 500, generic `An unexpected error occurred` body in every case.
- **Expected:** HTTP 400 for empty/unparseable body (`HttpMessageNotReadableException`); HTTP 415 Unsupported Media Type for wrong/missing Content-Type. Both should produce structured error bodies with a useful message — never 500.

### F-READING-LISTS-003: Invalid UUID in path returns 500 instead of 400
- **Endpoint:** `GET /api/reading-lists/{id}`, `PUT /api/reading-lists/{id}`, `DELETE /api/reading-lists/{id}/items/{documentId}` (and any other `{id}` / `{documentId}` path)
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/not-a-uuid
  curl -s -i -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/4a4c21ab-1c75-4573-b8fa-1de936d5fd14/items/not-a-uuid
  ```
- **Observed:** HTTP 500, `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}`.
- **Expected:** HTTP 400 Bad Request with a message indicating the path variable could not be parsed as a UUID. Spring throws `MethodArgumentTypeMismatchException` here; the global handler should map it to 400.

### F-READING-LISTS-004: Bad sort expression on listing returns 500 instead of 400
- **Endpoint:** `GET /api/reading-lists`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?sort=garbage"
  curl -s -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?sort=name';drop+table+x;--"
  ```
- **Observed:** HTTP 500 (`PropertyReferenceException` is leaking through the global handler).
- **Expected:** HTTP 400 with a message indicating the sort property is unknown, or — better — restrict the sort whitelist. Currently any unknown property bubbles up as a server error.
- **Notes:** Not a SQL-injection vector (Spring Data validates against known properties before hitting the DB), but the user-controlled error path is still 500 instead of 400.

### F-READING-LISTS-005: Invalid pagination params silently accepted (page=-1, size=99999, page=abc, size=-5)
- **Endpoint:** `GET /api/reading-lists`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?page=-1&size=5"
  curl -s -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?size=99999"
  curl -s -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?page=abc"
  curl -s -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?size=-5"
  ```
- **Observed:** HTTP 200 in every case; negative/non-numeric `page` and `size` silently fall back to defaults; `size=99999` is honoured (no upper bound enforced).
- **Expected:** HTTP 400 for malformed input (`page=abc`, `page=-1`, `size=-5`). At minimum, enforce a server-side `max-page-size`. Silent fallback hides client bugs and lets a single request request unbounded data.

### F-READING-LISTS-006: Pagination response shape doesn't match the documented contract
- **Endpoint:** `GET /api/reading-lists`
- **Severity:** Medium
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/reading-lists?page=0&size=2" | python3 -m json.tool
  ```
- **Observed:** Response uses Spring's default `PageImpl` JSON shape — top-level keys `content`, `empty`, `first`, `last`, `number`, `numberOfElements`, `pageable: {...}`, `size`, `sort: {...}`, `totalElements`, `totalPages`. The whole `pageable` and `sort` nested objects with `empty`/`paged`/`unpaged`/`offset` are leaked.
- **Expected:** Per `docs/rest-api.md` lines 399-410, the shape is documented as `{ content, page, size, totalElements, totalPages, last }`. The actual response is missing `page` (it's named `number`) and contains many extra fields. Either document `PageImpl` honestly or wrap it in a stable response DTO. The current shape is also a known Spring deprecation point — Spring 3.x logs a warning about returning `Page` directly from REST controllers because the format is unstable across versions.

### F-READING-LISTS-007: 403 leaks for resources that don't exist (no 404 distinction)
- **Endpoint:** `GET /api/reading-lists/{id}`, `PUT /api/reading-lists/{id}`, `DELETE /api/reading-lists/{id}`, `POST /api/reading-lists/{id}/items`, `DELETE /api/reading-lists/{id}/items/{documentId}`
- **Severity:** Medium
- **Category:** auth
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  NONEXIST=11111111-2222-3333-4444-555555555555
  curl -s -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/$NONEXIST
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"x"}' http://localhost:8080/api/reading-lists/$NONEXIST
  curl -s -i -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/$NONEXIST
  ```
- **Observed:** HTTP 403 `Access denied` for every nonexistent UUID; same 403 is returned for "list owned by another user".
- **Expected:** A nonexistent resource should be 404 Not Found, distinct from 403 Forbidden. Returning the same code for both can be a legitimate hardening choice (don't leak existence), but combined with finding F-READING-LISTS-008 (DELETE-then-DELETE → 403) it just looks like a broken `@PreAuthorize` chain (`ownership.isReadingListOwner` returns `false` when the entity is missing). At minimum, document the policy explicitly. As-is the API cannot distinguish "you don't own this" from "this never existed" from "this was just deleted".

### F-READING-LISTS-008: DELETE-then-DELETE on the list returns 403, not 404
- **Endpoint:** `DELETE /api/reading-lists/{id}`
- **Severity:** Medium
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d '{"name":"to-delete"}' http://localhost:8080/api/reading-lists | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
  curl -s -o /dev/null -w 'first:%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/$ID
  curl -s -o /tmp/b -w 'second:%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/$ID
  cat /tmp/b
  ```
- **Observed:** First DELETE → 204. Second DELETE → 403 `Access denied`. GET on the just-deleted list also → 403.
- **Expected:** Second DELETE should be 404 Not Found (idempotent semantics for DELETE: repeated calls behave identically and report that the resource is gone). 403 here is misleading because the caller did own the resource — it just isn't there anymore. This is a direct consequence of finding F-READING-LISTS-007: `@PreAuthorize` evaluates ownership against a now-missing entity and returns `false`.

### F-READING-LISTS-009: `name` field accepts non-string types via Jackson coercion
- **Endpoint:** `POST /api/reading-lists`, `PUT /api/reading-lists/{id}`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d '{"name":12345}' http://localhost:8080/api/reading-lists
  ```
- **Observed:** HTTP 201, list created with `"name":"12345"` (the integer is coerced to its string representation).
- **Expected:** HTTP 400 — strict APIs should reject `{"name":12345}` because the contract says `name` is a string. Jackson's default `MapperFeature.ALLOW_COERCION_OF_SCALARS` is enabled; per the project's "Make APIs strict — fail fast" guideline this should be turned off.

### F-READING-LISTS-010: Oversize `name` triggers 500 instead of 400
- **Endpoint:** `POST /api/reading-lists`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  LONG=$(python3 -c "print('x'*5000)")
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d "{\"name\":\"$LONG\"}" http://localhost:8080/api/reading-lists
  ```
- **Observed:** HTTP 500 (DB constraint violation propagates through the global handler).
- **Expected:** HTTP 400 with a `name length must be <= N` message. The DTO should carry `@Size(max=...)` matching the column, and DB constraint errors should be mapped to 400 by the global handler rather than 500.

### F-READING-LISTS-011: Unknown JSON properties silently ignored on POST/PUT
- **Endpoint:** `POST /api/reading-lists`, `PUT /api/reading-lists/{id}`, `POST /api/reading-lists/{id}/items`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d '{"name":"With extras","createdAt":"1970-01-01T00:00:00Z","items":[{"foo":"bar"}],"id":"00000000-0000-0000-0000-000000000000"}' \
       http://localhost:8080/api/reading-lists
  ```
- **Observed:** HTTP 201, extra fields silently dropped, server-generated id/createdAt used.
- **Expected:** Per "make APIs strict" guideline, `FAIL_ON_UNKNOWN_PROPERTIES` should be enabled so client typos are caught (`{"naem": "..."}` would otherwise create a list with a blank name and the server would 400 — that's fine — but `{"name":"...","createdAtt":"..."}` should also fail clearly). Currently typos in optional fields are invisible to clients.

### F-READING-LISTS-012: Missing `Location` header on POST `/{id}/items`
- **Endpoint:** `POST /api/reading-lists/{id}/items`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d '{"documentId":"609a8cf7-cf54-4ab6-9edc-3aef8534a928"}' \
       http://localhost:8080/api/reading-lists/$LIST_ID/items
  ```
- **Observed:** HTTP 201 with body, but no `Location` header (the controller method uses `@ResponseStatus(HttpStatus.CREATED)` and returns the DTO directly).
- **Expected:** A 201 should set `Location: /api/reading-lists/{id}/items/{documentId}` per RFC 7231. `POST /api/reading-lists` does this correctly, so the contract is inconsistent across the controller.

### F-READING-LISTS-013: Error response shape is project-defined, not RFC 7807
- **Endpoint:** all reading-lists endpoints
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:** Any 4xx response, e.g. `curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' http://localhost:8080/api/reading-lists`.
- **Observed:** `{ "status": int, "error": "Reason-Phrase", "message": "...", "timestamp": "...", "path": "..." }`.
- **Expected:** The shape is internally consistent across reading-lists endpoints (good) but is not RFC 7807 (`application/problem+json` with `type`/`title`/`status`/`detail`/`instance`). For new APIs this is the modern default. The `Content-Type` of the error response is `application/json`, not `application/problem+json`. For 400 validation errors there is no field-level error breakdown — only a single `message` string, so a client can't render per-field errors.

### F-READING-LISTS-014: 401 responses have no body
- **Endpoint:** all reading-lists endpoints when called without a valid token
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:**
  ```bash
  curl -s -i http://localhost:8080/api/reading-lists
  curl -s -i -H "Authorization: Bearer not-a-jwt" http://localhost:8080/api/reading-lists
  ```
- **Observed:** HTTP 401 with empty body, no `WWW-Authenticate` header advertising the scheme.
- **Expected:** RFC 7235 requires a `WWW-Authenticate` header on 401 (e.g. `WWW-Authenticate: Bearer realm="alexandria"`). Returning the standard error envelope (per F-READING-LISTS-013) so 401s match other error shapes would also help clients.

### F-READING-LISTS-015: 409 conflict on duplicate item — undocumented but well-handled
- **Endpoint:** `POST /api/reading-lists/{id}/items`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  # Add the same documentId twice
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d '{"documentId":"609a8cf7-cf54-4ab6-9edc-3aef8534a928"}' \
       http://localhost:8080/api/reading-lists/$LIST_ID/items
  ```
- **Observed:** Second call returns HTTP 409 `{"message":"Document ... is already in reading list ..."}` — clean, useful behaviour.
- **Expected:** This is the right code, but it is not documented in `docs/rest-api.md`. Add `409 Conflict` to the response table for `POST /api/reading-lists/{id}/items`.

### F-READING-LISTS-016: 404 for missing document on add-item — undocumented
- **Endpoint:** `POST /api/reading-lists/{id}/items`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       -d '{"documentId":"99999999-9999-9999-9999-999999999999"}' \
       http://localhost:8080/api/reading-lists/$LIST_ID/items
  ```
- **Observed:** HTTP 404 `{"message":"Document not found: 99999999-9999-9999-9999-999999999999"}`.
- **Expected:** Correct behaviour, but document the 404 case in `docs/rest-api.md` so clients know to handle it. This is also slightly REST-debatable — some APIs return 422 Unprocessable Entity when a referenced (not pathed) resource is missing — but 404 is acceptable.

## Endpoints with no findings
- `POST /api/reading-lists` happy path (returns 201 with body and Location header)
- `GET /api/reading-lists/{id}` happy path (returns 200 with documented body)
- `PUT /api/reading-lists/{id}` happy path (returns 200 with updated body)
- `DELETE /api/reading-lists/{id}` happy path (returns 204)
- `POST /api/reading-lists/{id}/items` happy path body shape (matches docs — but see F-READING-LISTS-012 for the missing Location header)
- `DELETE /api/reading-lists/{id}/items/{documentId}` happy path (returns 204) and DELETE-then-DELETE on an item (returns 404 second time — correct idempotent semantics, contrast with F-READING-LISTS-008 on the list itself)
- `OPTIONS /api/reading-lists` (returns 200 with correct `Allow: GET,HEAD,POST,OPTIONS`)
- Cross-user authorization on all `{id}`-scoped endpoints (returns 403 consistently — see also F-READING-LISTS-007 for the not-found-vs-forbidden caveat)
- Authentication on all endpoints rejects no-token / malformed-JWT / wrong-scheme with 401 (no 500/403 confusion — see F-READING-LISTS-014 for the missing body and `WWW-Authenticate` header)

---

## Verification pass

| Finding ID | Status | Note |
|---|---|---|
| F-READING-LISTS-001 | CONFIRMED | Reproduced 500 on every disallowed-method probe (PATCH/PUT/DELETE on `/api/reading-lists`, POST/PATCH on `/{id}`, GET/PUT/DELETE on `/{id}/items`, GET/PUT on `/{id}/items/{documentId}`); `OPTIONS` with auth correctly returns `Allow: GET,HEAD,POST,OPTIONS` so Spring knows the right answer. |
| F-READING-LISTS-002 | CONFIRMED | All seven probes (empty body, malformed JSON, text/plain, missing CT, application/xml, PUT empty, POST items empty) returned 500 with the generic envelope. |
| F-READING-LISTS-003 | CONFIRMED | `GET /api/reading-lists/not-a-uuid` and `DELETE /{id}/items/not-a-uuid` both 500. |
| F-READING-LISTS-004 | CONFIRMED | `?sort=garbage` and the SQL-injection-shaped sort both 500. |
| F-READING-LISTS-005 | CONFIRMED | `page=-1`, `size=99999`, `page=abc`, `size=-5` all returned 200 with silent fallback. |
| F-READING-LISTS-006 | CONFIRMED | Response uses `number`/`pageable`/`sort` PageImpl shape; docs claim `page` and a flat shape. |
| F-READING-LISTS-007 | CONFIRMED | All three nonexistent-UUID probes returned 403, not 404. |
| F-READING-LISTS-008 | CONFIRMED | First DELETE → 204; second DELETE → 403 with `Access denied`. |
| F-READING-LISTS-009 | CONFIRMED | `{"name":12345}` → 201 with `"name":"12345"`. |
| F-READING-LISTS-010 | CONFIRMED | 5000-char `name` → 500 (DB constraint propagated). |
| F-READING-LISTS-011 | CONFIRMED | Unknown fields (`createdAt`, `items`, `id`) silently dropped, server-generated values used, 201 returned. |
| F-READING-LISTS-012 | CONFIRMED | `POST /{id}/items` returned 201 without a `Location` header; `POST /api/reading-lists` does set `Location`, so the inconsistency is real. |
| F-READING-LISTS-013 | CONFIRMED | All 4xx bodies use the project envelope (`status`/`error`/`message`/`timestamp`/`path`); `Content-Type: application/json`, no `application/problem+json`, no field-level error breakdown. |
| F-READING-LISTS-014 | CONFIRMED | Missing token and malformed JWT both return 401 with `Content-Length: 0` and no `WWW-Authenticate` header. |
| F-READING-LISTS-015 | CONFIRMED | Re-adding an existing item returns 409 with a clean message; not in `docs/rest-api.md`. |
| F-READING-LISTS-016 | CONFIRMED | Adding a non-existent `documentId` returns 404 with a clean message; not in `docs/rest-api.md`. |

### Additional findings from verification
- F-READING-LISTS-017 (validation, High): `POST /api/reading-lists/{id}/items` with `{"documentId":"not-a-uuid"}` returns HTTP 500. Repro: `curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"documentId":"not-a-uuid"}' http://localhost:8080/api/reading-lists/$LIST_ID/items`. Expected 400 Bad Request — Jackson UUID-deserialization failure should be mapped, same root cause as F-002. Distinct from F-003 (which is path-variable parsing) because here the bad UUID is in the JSON body.
- F-READING-LISTS-018 (rest-best-practice / routing, Medium): Trailing slash on a collection path triggers HTTP 500. Repro: `curl -s -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reading-lists/`. Expected either 200 (treat trailing slash as equivalent — Spring's old default) or a clean 404/308. Currently the trailing slash is being routed to `GET /api/reading-lists/{id}` and exploding inside `MethodArgumentTypeMismatchException` handling, which the global handler turns into a generic 500. Same handler bug as F-003.
- F-READING-LISTS-019 (content-negotiation, Medium): Authenticated `GET /api/reading-lists` with `Accept: application/xml` (or `text/html`) returns HTTP 401, not 406 Not Acceptable. The `Accept` response header on the 401 lists the supported types (`application/json, application/*+json, application/yaml`), confirming the server knew it was a content-type mismatch. Repro: `curl -s -i -H "Authorization: Bearer $TOKEN" -H "Accept: application/xml" http://localhost:8080/api/reading-lists` → `HTTP/1.1 401`. Wrong status code (should be 406) and leaks an auth-related answer to a non-auth problem; this is a security/observability misclassification.

### Refuted findings — corrected understanding
- (none — every finding F-001 through F-016 reproduced exactly as described)
