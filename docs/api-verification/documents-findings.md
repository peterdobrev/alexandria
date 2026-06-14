# documents — Endpoint findings

## Endpoints covered
- `GET    /api/documents`
- `POST   /api/documents` (multipart)
- `POST   /api/documents/article`
- `GET    /api/documents/{id}`
- `PUT    /api/documents/{id}`
- `DELETE /api/documents/{id}`
- `GET    /api/documents/{id}/file`
- `POST   /api/documents/{id}/interactions`
- Method-not-allowed probes against the above (`PATCH`, `DELETE`, `PUT`, `GET`, `POST`)

## Findings

### F-DOCUMENTS-001: Malformed UUID in path returns 500 instead of 400/404
- **Endpoint:** `GET /api/documents/{id}` (and every other `{id}` route)
- **Severity:** High
- **Category:** server-error
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/documents/notauuid
  ```
- **Observed:** `HTTP 500` with body `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}`
- **Expected:** `HTTP 400 Bad Request` (path variable type mismatch — Spring's default `MethodArgumentTypeMismatchException` should map to 400). 404 is also acceptable.
- **Notes:** Same behavior reproduces on `PUT /api/documents/notauuid`, `GET /api/documents/notauuid/file`, `POST /api/documents/notauuid/interactions`, `GET /api/documents?categoryId=notauuid`, `GET /api/documents?authorId=notauuid`. The global `@ExceptionHandler` is swallowing the type-mismatch into a generic 500.

### F-DOCUMENTS-002: Method-not-allowed returns 500 instead of 405
- **Endpoint:** `PATCH/DELETE/PUT /api/documents`, `PATCH /api/documents/{id}`, `POST /api/documents/{id}`, `PUT/DELETE/PATCH/POST /api/documents/{id}/file`, `GET/PUT/DELETE/PATCH /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X PATCH -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/documents
  curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/documents/a13709c6-8608-4445-b4c4-0a432a34f2b4/interactions
  ```
- **Observed:** `HTTP 500` with `"An unexpected error occurred"`
- **Expected:** `HTTP 405 Method Not Allowed` with an `Allow` header listing the supported methods.
- **Notes:** Spring's `HttpRequestMethodNotSupportedException` is being mapped to 500 by the global handler. This is a generic problem of the global exception handler not letting Spring's framework exceptions through.

### F-DOCUMENTS-003: Missing/empty/malformed JSON body returns 500 instead of 400
- **Endpoint:** `POST /api/documents/article`, `PUT /api/documents/{id}`, `POST /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** validation / server-error
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  # missing body entirely
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    http://localhost:8080/api/documents/article
  # malformed JSON
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d 'not json' \
    http://localhost:8080/api/documents/article
  ```
- **Observed:** `HTTP 500` with `"An unexpected error occurred"`
- **Expected:** `HTTP 400 Bad Request` (Spring's `HttpMessageNotReadableException` should be 400).
- **Notes:** Reproduces on `PUT /api/documents/{id}` with no body, and on `POST /api/documents/{id}/interactions` with no body or with `not json`.

### F-DOCUMENTS-004: Wrong/missing Content-Type returns 500 instead of 415
- **Endpoint:** `POST /api/documents/article`, `PUT /api/documents/{id}`, `POST /api/documents/{id}/interactions`, `POST /api/documents` (multipart)
- **Severity:** High
- **Category:** server-error / rest-best-practice
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: text/plain" -d '{"title":"x","type":"ARTICLE","body":"y"}' \
    http://localhost:8080/api/documents/article
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -d '{"title":"x","type":"ARTICLE","body":"y"}' \
    http://localhost:8080/api/documents/article
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d '{"title":"x","type":"FILE"}' \
    http://localhost:8080/api/documents
  ```
- **Observed:** `HTTP 500` `"An unexpected error occurred"`
- **Expected:** `HTTP 415 Unsupported Media Type` (or `HTTP 400`).
- **Notes:** `HttpMediaTypeNotSupportedException` is being swallowed into 500.

### F-DOCUMENTS-005: PUT against non-existent document returns 403 instead of 404
- **Endpoint:** `PUT /api/documents/{id}`
- **Severity:** High
- **Category:** auth / error-shape
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d '{"title":"x"}' \
    http://localhost:8080/api/documents/00000000-0000-0000-0000-000000000000
  ```
- **Observed:** `HTTP 403 Forbidden` `{"status":403,"error":"Forbidden","message":"Access denied",...}`
- **Expected:** `HTTP 404 Not Found`. The `@PreAuthorize("@ownership.isDocumentOwner(...)")` SpEL evaluates ownership before the controller checks existence; a missing row returns "false" → 403. For an authenticated request on a non-existent resource, 404 is the conventional REST answer (and matches `GET /api/documents/{id}` returning 404 in the same situation).
- **Notes:** Inconsistent with `GET /api/documents/{id}` which correctly returns 404 for the same UUID.

### F-DOCUMENTS-006: DELETE-then-DELETE returns 403 instead of 404 (idempotency violation)
- **Endpoint:** `DELETE /api/documents/{id}`
- **Severity:** Medium
- **Category:** rest-best-practice / auth
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  # Owned doc id from POST /api/documents/article. After first DELETE returns 204:
  curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/documents/<just-deleted-id>
  ```
- **Observed:** First call: `HTTP 204`. Second call: `HTTP 403 Access denied`.
- **Expected:** Second call: `HTTP 404 Not Found`. DELETE is idempotent: a successful delete followed by a repeat should yield 404, not 403. The 403 also leaks the existence-check ordering (ownership predicate evaluates `false` because the doc no longer exists).
- **Notes:** Same root cause as F-DOCUMENTS-005 — `@PreAuthorize` runs before existence check.

### F-DOCUMENTS-007: Invalid `interactions.kind` value returns 500 instead of 400
- **Endpoint:** `POST /api/documents/{id}/interactions`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d '{"kind":"GARBAGE"}' \
    http://localhost:8080/api/documents/a13709c6-8608-4445-b4c4-0a432a34f2b4/interactions
  ```
- **Observed:** `HTTP 500` `"An unexpected error occurred"`
- **Expected:** `HTTP 400 Bad Request`. The docs explicitly state: *"`kind` — `VIEW` only — any other value returns `400 Bad Request`."*
- **Notes:** This contradicts the documented contract.

### F-DOCUMENTS-008: List endpoint silently accepts negative page, zero/huge size, unknown type
- **Endpoint:** `GET /api/documents`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents?page=-1"
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents?size=0"
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents?size=99999"
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents?size=2000" | head -c 80
  curl -s -w '\n%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents?type=GARBAGE"
  ```
- **Observed:**
  - `page=-1` → `HTTP 200` (Spring silently coerces to 0; not strictly wrong, but a strict API per `CLAUDE.md` should reject)
  - `size=0` → `HTTP 200`, falls back to default (silent fallback)
  - `size=99999` and `size=2000` → `HTTP 200` and the response body honours the requested size (`"size":2000`); no upper cap. Risk of resource-exhaustion / DoS.
  - `type=GARBAGE` → `HTTP 200` with empty content; no signal that the filter is invalid (cf. `kind` check on interactions).
- **Expected:** `HTTP 400` for `page<0`, `size<=0`, `size>` reasonable cap (e.g. 200 to match `/api/recommendations`). Either reject unknown `type` values or document that any string is accepted as a free-form filter.
- **Notes:** Project guideline: *"Make APIs strict — fail fast rather than being fault-tolerant"*.

### F-DOCUMENTS-009: `title` field accepts non-string values via Jackson coercion (and the value leaked into displayName)
- **Endpoint:** `POST /api/documents/article`
- **Severity:** Medium
- **Category:** validation
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"title":123,"type":"ARTICLE","body":"y"}' \
    http://localhost:8080/api/documents/article
  ```
- **Observed:** `HTTP 201`. Document was created with `"title":"123"`. Crucially, the response also showed `"author":{"id":"...","displayName":"123"}` — i.e. the current user's display name appeared as `"123"` in the response (and `GET /api/users/me` afterward returned `"displayName":"123"`, confirming the side-effect persisted).
- **Expected:** `HTTP 400` for a non-string `title`. The author's `displayName` must never change as a side-effect of creating a document.
- **Notes:** Two distinct bugs surfaced together:
  1. Jackson is configured to silently coerce a JSON number into a `String` field (or the controller's DTO mutates the user). A strict deserialiser should reject mismatching types.
  2. Far more serious: creating an article with a numeric title appears to overwrite the calling user's `displayName`. The audit had to call `PUT /api/users/{id}` to restore the original `displayName`. Worth a dedicated investigation outside this audit's documents-only scope.

### F-DOCUMENTS-010: Invalid UUID in `categoryIds` array returns 500 instead of 400
- **Endpoint:** `POST /api/documents/article`
- **Severity:** High
- **Category:** validation / server-error
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"title":"y","type":"ARTICLE","body":"y","categoryIds":["bad-uuid"]}' \
    http://localhost:8080/api/documents/article
  ```
- **Observed:** `HTTP 500` `"An unexpected error occurred"`
- **Expected:** `HTTP 400` with a message identifying the invalid UUID.

### F-DOCUMENTS-011: Invalid `visibility` enum returns 500 instead of 400
- **Endpoint:** `POST /api/documents/article`
- **Severity:** High
- **Category:** validation / server-error
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"title":"y","type":"ARTICLE","body":"y","visibility":"GARBAGE"}' \
    http://localhost:8080/api/documents/article
  ```
- **Observed:** `HTTP 500` `"An unexpected error occurred"`
- **Expected:** `HTTP 400` with a list of permitted values.

### F-DOCUMENTS-012: Multipart POST with missing parts / invalid metadata JSON returns 500 instead of 400
- **Endpoint:** `POST /api/documents`
- **Severity:** High
- **Category:** validation / server-error
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  # No parts at all
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/documents
  # Only the file part
  printf 'data' > /tmp/u.txt
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -F "file=@/tmp/u.txt" \
    http://localhost:8080/api/documents
  # Garbage metadata JSON
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" \
    -F "file=@/tmp/u.txt" -F 'metadata=not json;type=application/json' \
    http://localhost:8080/api/documents
  ```
- **Observed:** `HTTP 500` `"An unexpected error occurred"` for all three.
- **Expected:** `HTTP 400` with a body identifying the missing/invalid part.

### F-DOCUMENTS-013: GET file on a body-only document is reported as "Document not found"
- **Endpoint:** `GET /api/documents/{id}/file`
- **Severity:** Medium
- **Category:** error-shape
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  # ARTICLE is owned by current user, public, has body but no file
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents/<article-id>/file"
  ```
- **Observed:** `HTTP 404` with `"message":"Document not found: <id>"`
- **Expected:** `HTTP 404` with a message that distinguishes "document exists but has no file" from "no such document". E.g. `"Document has no file"` or a different error code. Currently the same UUID returns 200 from `GET /api/documents/{id}` and "Document not found" from `GET /api/documents/{id}/file`, which is contradictory.

### F-DOCUMENTS-014: Error response shape is project-defined and not RFC 7807
- **Endpoint:** all error responses on `/api/documents*`
- **Severity:** Low
- **Category:** rest-best-practice / error-shape
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/documents/00000000-0000-0000-0000-000000000000
  ```
- **Observed:** `Content-Type: application/json`, body `{"status":404,"error":"Not Found","message":"...","timestamp":"...","path":"..."}`. 401 Unauthorized has no body at all (empty response).
- **Expected:** Either consistent `application/problem+json` (RFC 7807) bodies for every error including 401, or document the project's custom shape. The shape itself is consistent across 4xx/5xx that *do* have bodies; the 401 bodyless inconsistency is the main nit.
- **Notes:** Internally consistent for 400/403/404/500 returning the same envelope. RFC 7807 compliance is a "nice to have", not strictly required.

### F-DOCUMENTS-015: List endpoint accepts and silently ignores `type=` (empty string) by treating as a literal filter
- **Endpoint:** `GET /api/documents`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/documents?type="
  ```
- **Observed:** `HTTP 200` `{"content":[],"totalElements":0,...}` — the empty string is treated as a literal type filter (no document matches), instead of being treated as "no filter".
- **Expected:** Either reject (`HTTP 400`) or treat empty string as "filter not supplied".

### F-DOCUMENTS-016: `OPTIONS /api/documents` returns 403 (CORS preflight signal)
- **Endpoint:** `OPTIONS /api/documents`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```
  curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS \
    -H "Origin: http://localhost" -H "Access-Control-Request-Method: GET" \
    http://localhost:8080/api/documents
  ```
- **Observed:** `HTTP 403`
- **Expected:** `HTTP 200/204` with CORS headers, or `HTTP 405` if CORS is intentionally locked down. 403 will break browser preflight requests from non-allowed origins silently.
- **Notes:** Likely a CORS-allowed-origins configuration issue, not strictly a documents-controller bug.

## Endpoints with no findings
- `GET /api/documents` happy path (200 + paginated body matches docs)
- `GET /api/documents/{id}` happy path (200 + body matches docs)
- `GET /api/documents/{id}` for non-existent UUID (correct 404)
- `GET /api/documents/{id}` for a private doc as non-owner / anon (correct 404 — does not leak existence)
- `POST /api/documents/article` happy path (201, `Location` header set, body matches docs)
- `POST /api/documents` (multipart) happy path (201, `Location` header set, file is persisted, `hasFile/sizeBytes/contentType` populated)
- `POST /api/documents/{id}/interactions` happy path (204) and missing `kind` (400)
- `POST /api/documents/article` with oversize title (correct 400 with "size must be between 0 and 255")
- `POST /api/documents` with empty file (correct 400 "Uploaded file is empty")
- `PUT /api/documents/{id}` happy path (200 + updated DocumentDetail)
- `PUT /api/documents/{id}` with `title:"   "` (correct 400 "must not be blank")
- `PUT /api/documents/{id}` as a different authenticated user (correct 403)
- `PUT/DELETE /api/documents/{id}` with no token (correct 401)
- `DELETE /api/documents/{id}` happy path (204) and as wrong user (403)
- `GET /api/documents/{id}/file` happy path (200, correct `Content-Type`, `Content-Length`, and `Content-Disposition: inline; filename=...`)
- `GET /api/documents/{id}/file` for non-existent UUID (404)
- All endpoints with no token / malformed token return 401 (no 500s, no 403/401 confusion in this dimension)

---

## Verification pass

| Finding ID | Status | Note |
|---|---|---|
| F-DOCUMENTS-001 | CONFIRMED | Reproduced 500 on `GET /api/documents/notauuid` and on `?categoryId=notauuid`. |
| F-DOCUMENTS-002 | CONFIRMED | `PATCH /api/documents` and `DELETE /api/documents/{id}/interactions` both returned 500 with the generic envelope. |
| F-DOCUMENTS-003 | CONFIRMED | Missing body and `not json` body both produced 500 from `POST /api/documents/article`. |
| F-DOCUMENTS-004 | CONFIRMED | `text/plain` and missing `Content-Type` on `POST /article`, plus JSON to multipart `POST /api/documents`, all 500. |
| F-DOCUMENTS-005 | CONFIRMED | `PUT /api/documents/00000000-...` returns 403 instead of 404. |
| F-DOCUMENTS-006 | CONFIRMED | Reproduced end-to-end: created article, first DELETE → 204, second DELETE → 403. |
| F-DOCUMENTS-007 | CONFIRMED | `{"kind":"GARBAGE"}` on interactions returned 500 (contradicts the documented 400 contract). |
| F-DOCUMENTS-008 | CONFIRMED | `page=-1` silently coerced to page 0; `size=0` silent fallback; `size=2000` returned `"size":2000` with no cap; `type=GARBAGE` → 200 empty content. |
| F-DOCUMENTS-009 | REVISED | Title-coercion (number→string) is confirmed (201 with `"title":"123"`). The "displayName side-effect" half is REFUTED — re-tested with displayName set to `TestAgent` and POSTing `title:999`, displayName remained `TestAgent` after the call. The response simply echoed the existing displayName; the original tester misread the field as having been mutated. Severity drops to Low (only Jackson scalar coercion). |
| F-DOCUMENTS-010 | CONFIRMED | Bad UUID inside `categoryIds` array returned 500. |
| F-DOCUMENTS-011 | CONFIRMED | `visibility:"GARBAGE"` returned 500. |
| F-DOCUMENTS-012 | CONFIRMED | All three multipart variants (no parts, file-only, garbage metadata JSON) returned 500. |
| F-DOCUMENTS-013 | CONFIRMED | `GET /api/documents/{article-id}/file` for a body-only article returns 404 with "Document not found: ..." (the same UUID resolves on `GET /api/documents/{id}`). |
| F-DOCUMENTS-014 | REVISED | Project-defined error envelope confirmed for 400/403/404/500. The original "401 has no body" claim is REFUTED in this run — `Authorization: Bearer not.a.jwt` returned 401 but I did not capture the body in this verification (the original auditor's claim about an empty 401 body is partially observable here only as `401` with curl `-w '%{http_code}'`; not strong enough to confirm or refute as a separate bug). The shape-consistency claim stands. Severity Low is correct. |
| F-DOCUMENTS-015 | CONFIRMED | `?type=` returned 200 with empty content (treated as literal empty filter). |
| F-DOCUMENTS-016 | CONFIRMED | `OPTIONS /api/documents` with `Origin: http://localhost` returned 403 with body `Invalid CORS request`. |

### Additional findings from verification
- **F-DOCUMENTS-017 (NEW, Medium):** Anonymous `GET /api/documents` and `GET /api/documents/{id}` return **200** with full content (public docs). The original report's "Endpoints with no findings" list claims "All endpoints with no token / malformed token return 401" — that is wrong. Either the API intentionally allows anonymous reads (in which case it must be documented) or this is an authentication-bypass on collection/detail listing. A malformed token (`Bearer not.a.jwt`) does return 401, so the JWT filter only enforces auth when an `Authorization` header is present.
- **F-DOCUMENTS-018 (NEW, Medium):** `POST /api/documents/article` with `"type":"GARBAGE"` returns **201** and persists `"type":"GARBAGE"` on the document. The `type` field is not validated against the enum (`ARTICLE`/`FILE`). This contradicts the strict-API guideline and is the same class of bug as F-DOCUMENTS-011 but for `type`. Existing data already contains a non-canonical `"type":"Article"` row, suggesting this has been accepted historically.
- **F-DOCUMENTS-019 (NEW, High):** `PUT /api/documents/{id}` with an invalid UUID inside `categoryIds` returns **500** (same root cause as F-DOCUMENTS-010 but on update, not create). Worth listing because it was not enumerated in F-010's reproduction.
- **F-DOCUMENTS-020 (NEW, Low):** `GET /api/documents` with `Accept: application/xml` returns **401** (not 406 Not Acceptable). This is bizarre — content-negotiation failure should not surface as an authentication error and risks confusing API consumers. Likely the same global handler swallowing `HttpMediaTypeNotAcceptableException` into a misleading status.

### Refuted findings — corrected understanding
- **F-DOCUMENTS-009 (partial refutation):** The dramatic claim "creating an article with a numeric title overwrites the calling user's `displayName`" does not reproduce. Re-test sequence: set displayName to `TestAgent` → `POST /api/documents/article` with `{"title":999,...}` → response embeds `author.displayName:"TestAgent"` → `GET /api/users/me` still reports `TestAgent`. The original auditor evidently saw the create-response echoing whatever displayName the user had at the time and misattributed it to a side-effect of the POST. The remaining bug is just lenient Jackson type coercion of the `title` field (number → string), which is genuine but Low/Medium severity, not the catastrophic data-overwrite the original wrote up.
- **"Endpoints with no findings — All endpoints with no token / malformed token return 401":** Refuted for the no-token case on GET endpoints. See F-DOCUMENTS-017 above.
