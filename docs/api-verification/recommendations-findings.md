# recommendations — Endpoint findings

## Endpoints covered
- `GET    /api/recommendations`
- `POST   /api/recommendations` (probe — not declared)
- `PUT    /api/recommendations` (probe — not declared)
- `PATCH  /api/recommendations` (probe — not declared)
- `DELETE /api/recommendations` (probe — not declared)
- `HEAD   /api/recommendations`
- `OPTIONS /api/recommendations`
- `POST   /api/documents/{id}/interactions`
- `GET    /api/documents/{id}/interactions` (probe — not declared)
- `PUT    /api/documents/{id}/interactions` (probe — not declared)
- `PATCH  /api/documents/{id}/interactions` (probe — not declared)
- `DELETE /api/documents/{id}/interactions` (probe — not declared)

## Findings

### F-RECOMMENDATIONS-001: Unsupported HTTP methods on `/api/recommendations` return 500 instead of 405
- **Endpoint:** `POST|PUT|PATCH|DELETE /api/recommendations`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w 'HTTP:%{http_code}\n' -X POST   -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/recommendations
  curl -s -w 'HTTP:%{http_code}\n' -X PUT    -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/recommendations
  curl -s -w 'HTTP:%{http_code}\n' -X PATCH  -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/recommendations
  curl -s -w 'HTTP:%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/recommendations
  ```
- **Observed:** `HTTP 500` with body
  ```json
  {"status":500,"error":"Internal Server Error","message":"An unexpected error occurred","timestamp":"...","path":"/api/recommendations"}
  ```
  for all four unsupported methods.
- **Expected:** `HTTP 405 Method Not Allowed` with an `Allow: GET, HEAD, OPTIONS` header. `OPTIONS` correctly reports the allowed set, so the framework knows it; the `HttpRequestMethodNotSupportedException` is being mishandled by the global exception handler and converted into a 500.
- **Notes:** The exception handler is shadowing a Spring-MVC standard signal. This affects every read-only collection endpoint in the project, not just recommendations.

### F-RECOMMENDATIONS-002: Unsupported HTTP methods on `/api/documents/{id}/interactions` return 500 instead of 405
- **Endpoint:** `GET|PUT|PATCH|DELETE /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC=19d7b5de-18b0-4805-a22d-9233e66d7f5e
  curl -s -w 'HTTP:%{http_code}\n' -X GET    -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/documents/$DOC/interactions
  curl -s -w 'HTTP:%{http_code}\n' -X PUT    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"kind":"VIEW"}' http://localhost:8080/api/documents/$DOC/interactions
  curl -s -w 'HTTP:%{http_code}\n' -X PATCH  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"kind":"VIEW"}' http://localhost:8080/api/documents/$DOC/interactions
  curl -s -w 'HTTP:%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/documents/$DOC/interactions
  ```
- **Observed:** `HTTP 500 Internal Server Error` with the generic error body.
- **Expected:** `HTTP 405 Method Not Allowed` with an `Allow: POST` header.
- **Notes:** Same global-handler issue as F-RECOMMENDATIONS-001. `GET` is particularly worth flagging: clients trying to read interactions get a 500, not a 404/405.

### F-RECOMMENDATIONS-003: Invalid enum value `kind` returns 500 instead of 400
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC=19d7b5de-18b0-4805-a22d-9233e66d7f5e
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '{"kind":"GARBAGE"}' http://localhost:8080/api/documents/$DOC/interactions
  ```
- **Observed:** `HTTP 500 {"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}`
- **Expected:** `HTTP 400 Bad Request` with a message naming the offending field (Jackson throws `InvalidFormatException` / `HttpMessageNotReadableException` here — both should be mapped to 400).
- **Notes:** Lower-case `view` and numeric `kind: 123` produce the same 500. By contrast `kind: null` and missing `kind` are correctly translated to a 400 by Bean Validation, so the gap is specifically the JSON-deserialization-time errors.

### F-RECOMMENDATIONS-004: Empty / malformed JSON body returns 500 instead of 400
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC=19d7b5de-18b0-4805-a22d-9233e66d7f5e
  # Empty body
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST http://localhost:8080/api/documents/$DOC/interactions
  # Malformed JSON
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '{not json' http://localhost:8080/api/documents/$DOC/interactions
  # Wrong shape (array instead of object)
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '[]' http://localhost:8080/api/documents/$DOC/interactions
  ```
- **Observed:** `HTTP 500` for all three.
- **Expected:** `HTTP 400 Bad Request` — `HttpMessageNotReadableException` is the canonical Spring signal for "client sent garbage JSON" and must not surface as 500.

### F-RECOMMENDATIONS-005: Wrong / missing Content-Type returns 500 instead of 415
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC=19d7b5de-18b0-4805-a22d-9233e66d7f5e
  # No Content-Type header
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
       -X POST -d '{"kind":"VIEW"}' http://localhost:8080/api/documents/$DOC/interactions
  # text/plain
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/plain' \
       -X POST -d '{"kind":"VIEW"}' http://localhost:8080/api/documents/$DOC/interactions
  ```
- **Observed:** `HTTP 500` in both cases.
- **Expected:** `HTTP 415 Unsupported Media Type` (the controller declares `@RequestBody` without an explicit consumes, but Spring's `HttpMediaTypeNotSupportedException` should be mapped to 415 by the global handler).

### F-RECOMMENDATIONS-006: Malformed UUID in path returns 500 instead of 400
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '{"kind":"VIEW"}' http://localhost:8080/api/documents/not-a-uuid/interactions
  ```
- **Observed:** `HTTP 500 {"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}`
- **Expected:** `HTTP 400 Bad Request` — Spring throws `MethodArgumentTypeMismatchException` for unparseable path UUIDs; the global handler should convert that to 400.

### F-RECOMMENDATIONS-007: Negative / zero pagination parameters silently fall back to defaults instead of being rejected
- **Endpoint:** `GET /api/recommendations`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/recommendations?page=-1" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('size:',d['size'],'page:',d['page'])"
  curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/recommendations?size=-1" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('size:',d['size'],'page:',d['page'])"
  curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/recommendations?size=0"  \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('size:',d['size'],'page:',d['page'])"
  curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/recommendations?page=abc" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('size:',d['size'],'page:',d['page'])"
  ```
- **Observed:** All four return `HTTP 200` with `page=0, size=20`. The API silently substitutes the default Pageable instead of failing.
- **Expected:** `HTTP 400 Bad Request`. The CLAUDE.md guideline "Make APIs strict — fail fast rather than being fault-tolerant" applies here, and the OpenAPI schema declares `size: minimum: 1` and `page: minimum: 0`. The controller already explicitly checks the upper bounds (`MAX_PAGE_SIZE`, `MAX_PAGE_NUMBER`) and rejects them with 400 — the asymmetry with the lower bound is a clear bug.

### F-RECOMMENDATIONS-008: `sort` query parameter accepts arbitrary garbage
- **Endpoint:** `GET /api/recommendations`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w 'HTTP:%{http_code}\n' -o /dev/null -H "Authorization: Bearer $TOKEN" \
       "http://localhost:8080/api/recommendations?sort=garbage"
  curl -s -w 'HTTP:%{http_code}\n' -o /dev/null -H "Authorization: Bearer $TOKEN" \
       "http://localhost:8080/api/recommendations?sort=title,wrongdir"
  ```
- **Observed:** `HTTP 200`. Both `sort=garbage` (non-existent property) and `sort=title,wrongdir` (invalid direction token) are silently accepted.
- **Notes:** Recommendations are computed in service code (the result is not actually sorted by `title` here), so `sort` is effectively a no-op for this endpoint. Either reject unknown sort fields (400) or document that `sort` is ignored. Currently the contract is a lie — the OpenAPI spec advertises `sort` but the endpoint does not honor it.

### F-RECOMMENDATIONS-009: 401 responses have empty body — inconsistent with the rest of the error shape
- **Endpoint:** `GET /api/recommendations`, `POST /api/documents/{id}/interactions` (any 401 path)
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:**
  ```bash
  curl -s -i http://localhost:8080/api/recommendations | head -15
  curl -s -i -H 'Authorization: Bearer not-a-jwt' http://localhost:8080/api/recommendations | head -15
  ```
- **Observed:** `HTTP 401`, `Content-Length: 0`, no JSON body.
- **Expected:** A JSON error body matching the shape used by all other error paths
  ```json
  {"status":401,"error":"Unauthorized","message":"...","timestamp":"...","path":"..."}
  ```
- **Notes:** Every other failure (400/404/500) on these endpoints returns the same six-field error envelope. 401 (and likely 403) bypass the global handler because they are emitted by Spring Security's `AuthenticationEntryPoint` before the dispatcher runs. Wire a JSON-emitting entry point for consistency.

### F-RECOMMENDATIONS-010: Generic `An unexpected error occurred` message hides actionable details from clients
- **Endpoint:** `GET /api/recommendations`, `POST /api/documents/{id}/interactions` (every 500 path observed in F-001..F-006)
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:** Any of the curl commands in F-001 through F-006.
- **Observed:** Every 500 returns the literal string `"An unexpected error occurred"` regardless of the underlying cause (method-not-supported, malformed-JSON, missing-content-type, type-mismatch, invalid-enum, etc.).
- **Expected:** Most of the listed root causes should never surface as 500 in the first place (see F-001..F-006). For genuine 500s, including a correlation/trace id (or at least the exception class) in the body would be more debuggable than a fixed string.
- **Notes:** Hiding internals from end users is fine; the issue is that the upstream taxonomy is wrong — many client-induced errors are being labelled "unexpected".

### F-RECOMMENDATIONS-011: 405 advertised by `OPTIONS` is never actually emitted
- **Endpoint:** `OPTIONS /api/recommendations`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X OPTIONS -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/recommendations
  ```
- **Observed:** `200 OK` with `Allow: GET,HEAD,OPTIONS`. Dispatching a non-allowed method (e.g. POST) does **not** then return 405 with the same `Allow` header — it returns 500 (see F-RECOMMENDATIONS-001).
- **Expected:** OPTIONS and 405 responses must be consistent: clients use `OPTIONS` to discover the surface and rightly expect a `405 + Allow` when they violate it.

### F-RECOMMENDATIONS-012: `GET` accepts a request body without complaint
- **Endpoint:** `GET /api/recommendations`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w 'HTTP:%{http_code}\n' -o /dev/null -H "Authorization: Bearer $TOKEN" \
       -H 'Content-Type: application/json' -X GET -d '{"foo":"bar"}' \
       http://localhost:8080/api/recommendations
  ```
- **Observed:** `HTTP 200`, full page returned. The body is silently ignored.
- **Notes:** RFC 9110 says GET request bodies have "no defined semantics"; servers that ignore them are technically conformant but allowing a `Content-Type: application/json` body on a read-only collection is a smell. Worth at least documenting that the body is ignored.

### F-RECOMMENDATIONS-013: `kind` field on POST is effectively useless — only `VIEW` is accepted
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  DOC=19d7b5de-18b0-4805-a22d-9233e66d7f5e
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '{"kind":"BOOKMARK"}' http://localhost:8080/api/documents/$DOC/interactions
  ```
- **Observed:** `HTTP 400 {"message":"Only VIEW interactions can be posted by clients", ...}`. The `CreateInteractionRequest` DTO declares `kind` as enum `{VIEW, BOOKMARK}`, the OpenAPI spec advertises both, but the controller rejects everything except `VIEW` at runtime (`RecommendationController.logInteraction`).
- **Expected:** Either remove `BOOKMARK` from the enum / OpenAPI surface (and the request body altogether — POST `/api/documents/{id}/interactions` with no body becomes a "log a view" action), or actually implement bookmark posting. Documenting an enum value the API rejects misleads SDK consumers.

### F-RECOMMENDATIONS-014: Endpoint `POST /api/documents/{id}/interactions` lacks server-side document ownership / visibility check
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** Low
- **Category:** auth
- **Reproduction:** Logging a `VIEW` for any well-formed UUID (whether the document is visible to the caller or not) is allowed; only "document does not exist" returns 404.
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w 'HTTP:%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '{"kind":"VIEW"}' http://localhost:8080/api/documents/00000000-0000-0000-0000-000000000000/interactions
  ```
- **Observed:** Existing-document POST → `204`. Non-existent UUID → `404 Document not found: ...`.
- **Notes:** Did not have a private foreign-owned document on hand to verify whether logging a VIEW against a `PRIVATE` document the caller cannot see is permitted (it likely is, given the controller goes straight to `interactionService.logView`). If recommendations are biased by VIEW interactions, this becomes an integrity-of-recommendations issue: a malicious client can push their own recommendation feed by spamming VIEWs against arbitrary document UUIDs they can't actually read. Consider gating `logView` on the same visibility predicate as `GET /api/documents/{id}`.

## Endpoints with no findings
- `GET /api/recommendations` — happy path (200, schema-correct page envelope)
- `GET /api/recommendations` with valid `page`, `size` (1..50) and over-limit `size=51`, `size=99999`, `page=201` (correct 400 with descriptive message)
- `HEAD /api/recommendations` (200, no body)
- `OPTIONS /api/recommendations` (200, `Allow: GET,HEAD,OPTIONS`) — caveat in F-011
- `POST /api/documents/{id}/interactions` happy path with `{"kind":"VIEW"}` (204)
- `POST /api/documents/{id}/interactions` with `{}` and `{"kind":null}` (400, Bean-Validation message)
- `POST /api/documents/{id}/interactions` with non-existent but well-formed document UUID (404 with descriptive message)
- `POST /api/documents/{id}/interactions` unauthenticated / garbage Bearer (401) — caveat in F-009
- `POST /api/documents/{id}/interactions` with extra unknown JSON fields (204 — Jackson lenient, acceptable)

---

## Verification pass

| Finding ID | Status | Note |
|---|---|---|
| F-RECOMMENDATIONS-001 | CONFIRMED | All four methods (POST/PUT/PATCH/DELETE) return 500 with the generic body. |
| F-RECOMMENDATIONS-002 | CONFIRMED | GET/PUT/PATCH/DELETE all return 500 with the generic body. |
| F-RECOMMENDATIONS-003 | CONFIRMED | `{"kind":"GARBAGE"}` returns 500 instead of 400. |
| F-RECOMMENDATIONS-004 | CONFIRMED | Empty body, malformed JSON, and array-shape body all return 500. |
| F-RECOMMENDATIONS-005 | CONFIRMED | No Content-Type and `text/plain` both return 500 (should be 415). |
| F-RECOMMENDATIONS-006 | CONFIRMED | `not-a-uuid` in path returns 500 instead of 400. |
| F-RECOMMENDATIONS-007 | CONFIRMED | `page=-1`, `size=-1`, `size=0`, `page=abc` all silently fall back to `page=0,size=20`. |
| F-RECOMMENDATIONS-008 | CONFIRMED | `sort=garbage` and `sort=title,wrongdir` both return 200 — silently accepted. |
| F-RECOMMENDATIONS-009 | CONFIRMED | 401 responses have `Content-Length: 0`, no JSON body. |
| F-RECOMMENDATIONS-010 | CONFIRMED | Every observed 500 in F-001..F-006 carries the literal `"An unexpected error occurred"` message. |
| F-RECOMMENDATIONS-011 | CONFIRMED | `OPTIONS` returns 200 with `Allow: GET,HEAD,OPTIONS` but disallowed methods return 500, not 405. |
| F-RECOMMENDATIONS-012 | CONFIRMED | `GET` with a JSON body returns 200; the body is silently ignored. |
| F-RECOMMENDATIONS-013 | CONFIRMED | `{"kind":"BOOKMARK"}` returns 400 with the message "Only VIEW interactions can be posted by clients" — the OpenAPI surface advertises an enum value that is rejected at runtime. |
| F-RECOMMENDATIONS-014 | CONFIRMED | Existing-doc POST → 204; non-existent UUID → 404. The visibility-bypass concern remains open (no foreign-private-doc test fixture available). |

### Additional findings from verification

- **F-RECOMMENDATIONS-015 (NEW): Trailing slash on `/api/recommendations/` returns 500 instead of 404.**
  - `GET /api/recommendations/` (note the trailing slash) returns
    `HTTP 500 {"status":500,"error":"Internal Server Error","message":"An unexpected error occurred","path":"/api/recommendations/"}`.
  - Spring Boot 3 dropped trailing-slash matching by default, so the framework is dispatching this to a no-handler path that gets translated into 500 by the global handler. The expected behavior is `404 Not Found` (or, if the team prefers, re-enable trailing-slash equivalence). Severity: **Medium** — same exception-handler shadowing class as F-001..F-006, just exposed via a routing miss instead of a method miss.

- **F-RECOMMENDATIONS-016 (NEW): Unsupported `Accept` header returns 401, not 406.**
  - Repro: `curl -i -H "Authorization: Bearer $TOKEN" -H "Accept: application/xml" http://localhost:8080/api/recommendations`
  - Observed: `HTTP 401`, empty body, and an `Accept: application/json, application/*+json, application/yaml` response header (a peculiar shape — server is advertising the producible types **on the 401**).
  - `Accept: text/html` reproduces it identically. `Accept: */*` works (200).
  - Expected: `406 Not Acceptable` with the producible-types list. Returning 401 is actively misleading — clients will think their token is bad when in fact the token was never even consulted.
  - Severity: **Medium** (security/error-shape — wrong status taxonomy plus auth-confusion).

- **F-RECOMMENDATIONS-017 (NEW): Unknown sub-path under interactions returns 500 instead of 404.**
  - Repro: `GET /api/documents/{id}/interactions/extra` returns `HTTP 500` with the generic envelope.
  - Expected: `404 Not Found`. Same root cause as F-001..F-006/F-015 — the global exception handler is catching `NoResourceFoundException` / `NoHandlerFoundException` and downgrading the status to 500.
  - Severity: **Medium**.

### Refuted findings — corrected understanding

- (none — all 14 original findings reproduced exactly as described)
