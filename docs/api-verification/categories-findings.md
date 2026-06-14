# categories — Endpoint findings

## Endpoints covered
- `GET /api/categories` (anonymous, with query params, with trailing slash)
- `POST /api/categories` (anonymous, non-admin user, garbage token, malformed `Authorization` header, empty body, malformed JSON, missing field, blank/whitespace name, wrong type, oversize name, `text/plain` Content-Type, missing Content-Type, extra unknown fields)
- `PUT /api/categories/{id}` (anonymous, non-admin user, invalid UUID in path, well-formed but non-existent UUID)
- `DELETE /api/categories/{id}` (anonymous, non-admin user, invalid UUID in path, well-formed but non-existent UUID)
- `GET /api/categories/{id}` (anonymous and authenticated — endpoint not declared)
- `PATCH /api/categories`, `PUT /api/categories`, `DELETE /api/categories` (collection — no handler)
- `POST /api/categories/{id}`, `PATCH /api/categories/{id}` (item — no handler for those verbs)
- `HEAD /api/categories`, `OPTIONS /api/categories`

> Notes on coverage limits: the pre-issued JWT in `/tmp/alexandria-test-token` belongs to a non-admin user. Direct DB access to promote the user to `ROLE_ADMIN` was denied by the harness. As a result, the admin-protected happy-paths for `POST /api/categories` (201 + Location), `PUT /api/categories/{id}` (200), and `DELETE /api/categories/{id}` (204) could not be exercised against a live admin token. All other audit dimensions were tested.

## Findings

### F-CATEGORIES-001: Unsupported HTTP methods on existing paths return 500 instead of 405
- **Endpoint:** `PATCH /api/categories`, `DELETE /api/categories`, `PUT /api/categories`, `POST /api/categories/{id}`, `PATCH /api/categories/{id}`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -o /dev/null -w '%{http_code}\n' -X PATCH http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"X"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X PATCH http://localhost:8080/api/categories/642b7cd2-ac3f-468f-a97f-3e821ebb15aa \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"X"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"X"}'
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/categories/642b7cd2-ac3f-468f-a97f-3e821ebb15aa \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"X"}'
  ```
- **Observed:** HTTP 500, body `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred", ...}`.
- **Expected:** HTTP 405 Method Not Allowed (with an `Allow` header listing supported verbs), or 404 if the path/verb pair is not modelled at all. A method mismatch is a client error, not a server error.
- **Notes:** `GlobalExceptionHandler` does not handle `org.springframework.web.HttpRequestMethodNotSupportedException`, so it falls through to the catch-all `@ExceptionHandler(Exception.class)`. Add a dedicated handler (or rely on Spring's `ResponseEntityExceptionHandler`) to map this to 405 with an `Allow` header.

### F-CATEGORIES-002: GET /api/categories/{id} returns 500 (handler is missing)
- **Endpoint:** `GET /api/categories/{id}`
- **Severity:** High
- **Category:** method / rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w '\nHTTP %{http_code}\n' \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/categories/642b7cd2-ac3f-468f-a97f-3e821ebb15aa
  ```
- **Observed:** HTTP 500, generic "An unexpected error occurred" body. Anonymous request returns 401 (because the security config requires auth on anything not explicitly permitted), but with auth the dispatcher fails on `NoHandlerFoundException` (or analogous) and the global handler converts it to 500.
- **Expected:** Either declare a `GET /api/categories/{id}` handler returning 200 with the `{id, name}` body (this is the natural REST counterpart to `POST`/`PUT`/`DELETE` on the same path, and the `Location` header that `POST /api/categories` already advertises points at exactly this URL), or at minimum return 404 / 405 — never 500.
- **Notes:** The `POST` handler builds a `Location` header at `/api/categories/{id}`. Per RFC 9110, `Location` should point to a resource that can be retrieved. Currently that URL produces a 500.

### F-CATEGORIES-003: 401 responses are empty and inconsistent with the documented error shape
- **Endpoint:** any protected categories endpoint with no/invalid token, e.g. `POST /api/categories`, `PUT /api/categories/{id}`, `DELETE /api/categories/{id}`
- **Severity:** Medium
- **Category:** error-shape / auth
- **Reproduction:**
  ```bash
  curl -sv -X POST http://localhost:8080/api/categories \
    -H "Content-Type: application/json" -d '{"name":"X"}'
  curl -sv -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer not.a.real.jwt" \
    -H "Content-Type: application/json" -d '{"name":"X"}'
  ```
- **Observed:** HTTP 401 with `Content-Length: 0` (empty body) and no `WWW-Authenticate` header. 403 responses on the *same* endpoints (with a valid non-admin token) return the project's `ErrorResponse` JSON shape `{status,error,message,timestamp,path}`.
- **Expected:** Either always return JSON `ErrorResponse` for 4xx (consistent with 400/403/404/409 in this project), or at minimum include a `WWW-Authenticate: Bearer ...` header per RFC 6750. The current empty-body 401 forces clients to special-case categories endpoints.
- **Notes:** The cause is `SecurityConfig`'s `authenticationEntryPoint((_, res, _) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))`. `sendError` does not emit a JSON body and bypasses `@RestControllerAdvice`. Replace it with an entry point that writes an `ErrorResponse` JSON.

### F-CATEGORIES-004: Empty body, malformed JSON, wrong Content-Type, missing Content-Type all return 500
- **Endpoint:** `POST /api/categories`, `PUT /api/categories/{id}`
- **Severity:** High
- **Category:** validation / server-error
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  # empty body
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d ''
  # malformed JSON
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":'
  # text/plain on a JSON endpoint
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" -d '{"name":"X"}'
  # missing Content-Type
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -d '{"name":"X"}'
  ```
- **Observed:** All four cases return HTTP 500 with the generic "An unexpected error occurred" body.
- **Expected:**
  - Empty body / malformed JSON → **400 Bad Request** (Spring throws `HttpMessageNotReadableException`).
  - `text/plain` (or any unsupported media type) → **415 Unsupported Media Type** (`HttpMediaTypeNotSupportedException`).
  - Missing Content-Type → **415** (or 400) — but never 500.
- **Notes:** `GlobalExceptionHandler` handles `MethodArgumentNotValidException` (post-binding bean validation) but not `HttpMessageNotReadableException` or `HttpMediaTypeNotSupportedException`. They fall through to the generic 500 handler. Add explicit handlers, or extend `ResponseEntityExceptionHandler` which already maps these.

### F-CATEGORIES-005: Invalid UUID in path returns 500 instead of 400
- **Endpoint:** `PUT /api/categories/{id}`, `DELETE /api/categories/{id}`
- **Severity:** High
- **Category:** validation / server-error
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w '\n%{http_code}\n' -X PUT    http://localhost:8080/api/categories/not-a-uuid \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"X"}'
  curl -s -w '\n%{http_code}\n' -X DELETE http://localhost:8080/api/categories/not-a-uuid \
    -H "Authorization: Bearer $TOKEN"
  ```
- **Observed:** HTTP 500, generic body.
- **Expected:** HTTP 400 Bad Request — a malformed path variable is a client error. Spring throws `MethodArgumentTypeMismatchException` for failed `UUID` conversion; the global handler doesn't cover it and it lands on the catch-all 500. Note the existing `IllegalArgumentException` handler maps to 400 but doesn't catch this subclass of `BindException`/`ConversionFailedException`.

### F-CATEGORIES-006: 403 leaks "category exists vs not" before authorization is checked
- **Endpoint:** `PUT /api/categories/{id}`, `DELETE /api/categories/{id}`
- **Severity:** Low
- **Category:** auth / rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)   # non-admin user
  # well-formed UUID that does not exist
  curl -s -w '\n%{http_code}\n' -X DELETE http://localhost:8080/api/categories/11111111-1111-1111-1111-111111111111 \
    -H "Authorization: Bearer $TOKEN"
  # well-formed UUID that DOES exist
  curl -s -w '\n%{http_code}\n' -X DELETE http://localhost:8080/api/categories/642b7cd2-ac3f-468f-a97f-3e821ebb15aa \
    -H "Authorization: Bearer $TOKEN"
  ```
- **Observed:** Both return HTTP 403 (Access denied) — same response regardless of whether the resource exists.
- **Expected:** This is actually fine behaviour for a public-listing resource (categories are world-readable via `GET /api/categories`), but it's worth documenting that **`@PreAuthorize` runs before service lookup** so a non-admin caller can't probe existence via 403-vs-404 differentiation. For owner-protected resources elsewhere this ordering would be the correct choice; here it merely means the endpoints can't distinguish "you're not admin" from "no such id" — which is acceptable. **No change needed**, but flagging because the ordering is the opposite of what some controllers do (404-before-403). Consistency check across the codebase recommended.

### F-CATEGORIES-007: ErrorResponse drops the structured `errorCode`
- **Endpoint:** any error path (e.g. `POST /api/categories` with a duplicate name would throw `CategoryAlreadyExistsException` → 409)
- **Severity:** Low
- **Category:** error-shape
- **Reproduction:** Examined source. `CategoryAlreadyExistsException` and `CategoryNotFoundException` carry `errorCode` strings (`CATEGORY_NAME_TAKEN`, `CATEGORY_NOT_FOUND`) but `ErrorResponse(status, error, message, timestamp, path)` and `GlobalExceptionHandler.build(...)` never include them.
- **Observed:** Body shape is `{"status":..., "error":..., "message":..., "timestamp":..., "path":...}` — no `errorCode`/`code` field.
- **Expected:** Include the `errorCode` in the response so clients can branch on it without parsing free-text messages. (Could not be reproduced live for categories without an admin token to trigger a duplicate; the mechanism is identical for any of the project's `NotFoundException`/`ConflictException` subclasses.)
- **Notes:** Either add `code` to `ErrorResponse` or adopt RFC 7807 `application/problem+json` with a `type` URI per error code.

### F-CATEGORIES-008: Trailing-slash variant `/api/categories/` returns 500
- **Endpoint:** `GET /api/categories/`
- **Severity:** Medium
- **Category:** rest-best-practice / server-error
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -w '\n%{http_code}\n' "http://localhost:8080/api/categories/" -H "Authorization: Bearer $TOKEN"
  ```
- **Observed:** HTTP 500, generic body. Anonymous request returns 401 because the trailing-slash form doesn't match the `/api/categories` permit-all rule.
- **Expected:** The same behaviour as `/api/categories` (200 OK, public). Spring Boot 3 disabled trailing-slash matching by default; the resulting `NoHandlerFoundException` is converted to 500 by the catch-all handler. Either enable `setUseTrailingSlashMatch(true)` (deprecated) / `PathPatternParser` redirect, or add a `NoHandlerFoundException` handler that returns 404. Not 500.

### F-CATEGORIES-009: Type-mismatch on body (e.g. `"name": 12345`) returns 403 instead of 400
- **Endpoint:** `POST /api/categories`
- **Severity:** Medium
- **Category:** validation / auth
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)   # non-admin user
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":12345}'
  ```
- **Observed:** HTTP 403 "Access denied".
- **Expected:** HTTP 400 — Jackson actually accepts `12345` for a `String` field by coercing it to `"12345"`, and then the `@PreAuthorize("hasRole('ADMIN')")` runs and rejects with 403. This is a leak: a non-admin caller can distinguish "Jackson would coerce" from "Jackson would fail" (which returns 500 — see F-CATEGORIES-004) just by looking at the status code. Disable lenient `String` coercion (`MapperFeature.ALLOW_COERCION_OF_SCALARS=false`) so the type mismatch is rejected with 400 *before* the auth check, and fix the 500 path so neither case leaks information.
- **Notes:** Less important than the explicit 500s above, but worth noting because it reveals that the service is silently coercing primitive values into strings.

### F-CATEGORIES-010: GET /api/categories silently ignores unknown query parameters (page/size/sort/etc.)
- **Endpoint:** `GET /api/categories`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  curl -s -w '\n%{http_code}\n' "http://localhost:8080/api/categories?page=-1&size=99999&sort=garbage&type=GARBAGE"
  ```
- **Observed:** HTTP 200 with the full unfiltered list (10 items). Garbage params are silently ignored.
- **Expected:** Either 400 on unknown query params (strict — fail-fast, matching CLAUDE.md "Make APIs strict") **or** at least documented as ignored. Categories may be small enough that pagination doesn't matter, but accepting `page`/`size`/`sort` and silently dropping them is a footgun for clients that copy-paste a documents-style query.
- **Notes:** Per CLAUDE.md "Make APIs strict — fail fast rather than being fault-tolerant", returning 200 here is a deviation from project guidelines.

### F-CATEGORIES-011: 201 Location header semantics not verified end-to-end (admin-only)
- **Endpoint:** `POST /api/categories`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:** Could not be exercised — no admin token available in this audit run.
- **Observed:** Source review only. Controller builds `Location: /api/categories/{id}` via `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(...)`.
- **Expected:** The URL pointed to by `Location` should be retrievable. Currently it is not (see F-CATEGORIES-002 — `GET /api/categories/{id}` returns 500). The two findings together mean the documented 201/Location contract is broken: a client that follows the `Location` header on success will hit a 500.
- **Notes:** Tightly coupled to F-CATEGORIES-002. Once a `GET` handler is added, this is fine.

## Endpoints with no findings
- `GET /api/categories` (anonymous, happy path) — 200, well-formed JSON array of `{id, name}`, matches docs.
- `POST /api/categories` validation handling for the *bean-validation* paths (missing `name`, blank, whitespace-only, oversize >255) — all correctly 400 with the validation message in `ErrorResponse`.
- `PUT /api/categories/{id}` and `DELETE /api/categories/{id}` AuthZ on non-admin / anon — 401 (anon) and 403 (non-admin) are returned with the correct status codes (the 401 body shape itself is flagged in F-CATEGORIES-003).
- `OPTIONS /api/categories` — 200 (CORS preflight handled correctly).
- `HEAD /api/categories` — 200 with valid token, 401 anonymous (consistent with the GET access rules).
