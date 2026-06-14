# users — Endpoint findings

## Endpoints covered
- `GET /api/users/me` (200 happy path, 401 no/garbage/expired token, 401 wrong scheme)
- `POST /api/users/me`
- `PUT /api/users/me`
- `PATCH /api/users/me`
- `DELETE /api/users/me`
- `OPTIONS /api/users/me`
- `HEAD /api/users/me`
- `GET /api/users/{id}` (happy, anonymous public read, unknown UUID, malformed UUID)
- `POST /api/users/{id}`
- `PUT /api/users/{id}` (happy, no auth, other-user, malformed UUID, bad/empty/no-CT body, oversize displayName, short/long password, wrong-type, unknown fields, xml/text-plain CT, Accept text/html)
- `PATCH /api/users/{id}`
- `DELETE /api/users/{id}`
- `GET /api/users/{id}/extra` (deeper path probe)
- `GET /api/users` and `GET /api/users/` (collection probes)

## Findings

### F-USERS-001: Unsupported HTTP methods on `/api/users/me` and `/api/users/{id}` return 500 instead of 405
- **Endpoint:** `POST|PATCH|DELETE /api/users/me` and `POST|PATCH|DELETE /api/users/{id}`
- **Severity:** High
- **Category:** method
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/users/me
  curl -s -i -X PATCH  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' http://localhost:8080/api/users/d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X POST   -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/users/d1691549-853a-45f4-995a-63850cffccee
  ```
- **Observed:** HTTP 500, body `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred",...}`
- **Expected:** HTTP 405 Method Not Allowed with an `Allow` header listing supported methods (`GET, HEAD, PUT, OPTIONS`).
- **Notes:** Spring would normally respond 405 here; the global exception handler is likely catching `HttpRequestMethodNotSupportedException` and remapping it to 500. `OPTIONS /api/users/me` already returns the correct `Allow: GET,HEAD,PUT,OPTIONS` so the metadata exists — only the error-mapping for 405 is broken. This is the same root cause as several other 500s in this report.

### F-USERS-002: Malformed UUID in path returns 500 instead of 400
- **Endpoint:** `GET|PUT /api/users/{id}` (any non-UUID segment except `me`)
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  curl -s -i http://localhost:8080/api/users/not-a-uuid
  TOKEN=$(cat /tmp/alexandria-test-token)
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{"displayName":"x"}' http://localhost:8080/api/users/not-a-uuid
  ```
- **Observed:** HTTP 500, generic "An unexpected error occurred" body.
- **Expected:** HTTP 400 Bad Request with a message like "Invalid UUID 'not-a-uuid' for path variable 'id'". Spring throws `MethodArgumentTypeMismatchException` here; the global handler should map it to 400.

### F-USERS-003: Malformed JSON / empty body returns 500 instead of 400
- **Endpoint:** `PUT /api/users/{id}`
- **Severity:** High
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{not json}' http://localhost:8080/api/users/$ID
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 500 in both cases.
- **Expected:** HTTP 400 with a parse-error message. `HttpMessageNotReadableException` is not being mapped by the global exception handler.

### F-USERS-004: Wrong / missing Content-Type returns 500 instead of 415
- **Endpoint:** `PUT /api/users/{id}`
- **Severity:** High
- **Category:** validation / error-shape
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  # No Content-Type
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -d '{"displayName":"x"}' http://localhost:8080/api/users/$ID
  # text/plain
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/plain' -d '{"displayName":"x"}' http://localhost:8080/api/users/$ID
  # application/xml
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -d '<x/>' http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 500 in all three cases.
- **Expected:** HTTP 415 Unsupported Media Type (or 400 for missing CT). `HttpMediaTypeNotSupportedException` is not handled by the global exception advice.

### F-USERS-005: Unsatisfiable `Accept` header returns 401 (auth entry-point) instead of 406
- **Endpoint:** `PUT /api/users/{id}` (and likely all endpoints) when called with `Accept: text/html` (no JSON variant)
- **Severity:** Medium
- **Category:** auth / error-shape
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Accept: text/html' \
       -H 'Content-Type: application/json' -d '{"displayName":"Verify Agent"}' \
       http://localhost:8080/api/users/$ID
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Accept: application/xml' \
       -H 'Content-Type: application/json' -d '{"displayName":"Verify Agent"}' \
       http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 401 with an empty body and `Accept: application/json, application/*+json, application/yaml` response header (echo of producible types). The user IS authenticated and authorised, yet the response is 401.
- **Expected:** HTTP 406 Not Acceptable with a JSON error body. Returning 401 here is misleading (the caller is correctly authenticated) and the empty body breaks the otherwise-consistent error shape.
- **Notes:** This appears to be Spring Security's `BearerTokenAuthenticationEntryPoint` being hit because the inner `HttpMediaTypeNotAcceptableException` propagates out as an unhandled error; security then fires its 401 entry point. Same family as F-USERS-001/003/004 — the global `@RestControllerAdvice` is missing the corresponding `@ExceptionHandler`s.

### F-USERS-006: 401 responses have an empty body — inconsistent with the rest of the error shape
- **Endpoint:** Any protected endpoint hit anonymously, e.g. `GET /api/users/me`, `PUT /api/users/{id}` without `Authorization` header.
- **Severity:** Medium
- **Category:** error-shape
- **Reproduction:**
  ```bash
  curl -s -i http://localhost:8080/api/users/me
  curl -s -i -X PUT -H 'Content-Type: application/json' -d '{"displayName":"Hacker"}' \
       http://localhost:8080/api/users/d1691549-853a-45f4-995a-63850cffccee
  ```
- **Observed:** `HTTP/1.1 401`, `Content-Length: 0`, no `Content-Type`, empty body.
- **Expected:** A JSON body matching the project's standard error shape (`{status, error, message, timestamp, path}`) — every other error in this controller uses that shape, including 403/404/400/500. Clients written to parse `error.message` will throw on 401.
- **Notes:** `BearerTokenAuthenticationEntryPoint` writes only the `WWW-Authenticate` and status; project should install a custom `AuthenticationEntryPoint` that emits the standard error JSON.

### F-USERS-007: Authorization failure on a non-existent user UUID leaks 403 instead of 404
- **Endpoint:** `PUT /api/users/{id}` with a well-formed UUID that does not exist in the database, while authenticated as some other user.
- **Severity:** Low
- **Category:** auth
- **Reproduction:**
  ```bash
  # SECOND_TOKEN is for a freshly-registered second user
  curl -s -i -X PUT -H "Authorization: Bearer $SECOND_TOKEN" -H 'Content-Type: application/json' \
       -d '{"displayName":"x"}' http://localhost:8080/api/users/00000000-0000-0000-0000-000000000099
  ```
- **Observed:** HTTP 403 `{"message":"Access denied"...}`.
- **Expected:** Either 404 (resource does not exist) or 403 — pick one and apply consistently. The current behaviour is acceptable from a security-leakage point of view (always-403 hides existence), but contradicts the "User not found" 404 returned by `GET /api/users/{id}` for the same UUID. Document the intended behaviour or align it.
- **Notes:** This is a "not-found vs forbidden" leakage choice. The two endpoints disagree: `GET /{id}` returns 404 for missing users, `PUT /{id}` returns 403 because `@ownership.isSelf` runs before existence is checked. From a strict REST viewpoint, the PUT side should still 404 when the path resource does not exist. From an information-leak viewpoint, both should be 403; right now you get the worst of both worlds.

### F-USERS-008: `displayName` validation accepts non-string JSON values without 400
- **Endpoint:** `PUT /api/users/{id}`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{"displayName": 123}' http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 200 with `displayName` silently coerced to the string `"123"` and persisted.
- **Expected:** HTTP 400 — strict APIs should reject a JSON number for a `String` field. Jackson is configured to coerce scalars to strings; consider `MapperFeature.ALLOW_COERCION_OF_SCALARS = false` or `DeserializationFeature.FAIL_ON_INVALID_SUBTYPE`-style strictness so the public API is "strict — fail fast" per project guidelines.

### F-USERS-009: Empty PUT body succeeds — verb/idempotency semantics
- **Endpoint:** `PUT /api/users/{id}` with `{}`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{}' http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 200, no fields changed.
- **Expected:** PUT semantics imply "replace the resource with the supplied representation" — sending `{}` arguably should null the optional fields, or the endpoint should be a `PATCH` instead. Today the controller treats every PUT field as optional and ignores omitted ones, which is `PATCH` semantics on a `PUT` verb. Either rename to `PATCH /api/users/{id}` or document that PUT is partial.
- **Notes:** Combined with F-USERS-008 (silent coercion) and F-USERS-010 (unknown fields silently dropped), this endpoint behaves laxly in a way the project guidelines explicitly discourage.

### F-USERS-010: Unknown fields in PUT body are silently dropped (no 400)
- **Endpoint:** `PUT /api/users/{id}`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{"email":"new@example.com","admin":true,"displayName":"Verify Agent"}' \
       http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 200; `email` and `admin` are silently ignored.
- **Expected:** Per project's "strict — fail fast" guideline, unknown fields should yield 400. Configure Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = true` (or `@JsonIgnoreProperties(ignoreUnknown = false)` on `UpdateUserRequest`).

### F-USERS-011: `GET /api/users` collection root returns 401 instead of 404/405
- **Endpoint:** `GET /api/users` and `GET /api/users/`
- **Severity:** Low
- **Category:** rest-best-practice / auth
- **Reproduction:**
  ```bash
  curl -s -i http://localhost:8080/api/users
  curl -s -i http://localhost:8080/api/users/
  ```
- **Observed:** HTTP 401 (empty body — see F-USERS-006).
- **Expected:** No collection endpoint exists, so 404 (resource not mapped) is more accurate. The current 401 implies "log in and you'll get something" which is misleading. This is also another instance of the "401 default" pattern hiding genuine 4xx.
- **Notes:** Spring Security applies its filter chain before dispatcher mapping; the protected default ends up returning 401 on any unmapped path under `/api/**`. Configure `securityMatcher` to permit unmapped routes through to the dispatcher so they 404 normally, or document that `/api/users` is intentionally not a collection endpoint.

### F-USERS-012: `displayName` accepts the empty string and whitespace-only values
- **Endpoint:** `PUT /api/users/{id}`
- **Severity:** Low
- **Category:** validation
- **Reproduction:**
  ```bash
  TOKEN=$(cat /tmp/alexandria-test-token)
  ID=d1691549-853a-45f4-995a-63850cffccee
  curl -s -i -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{"displayName":""}' http://localhost:8080/api/users/$ID
  ```
- **Observed:** HTTP 200 — the user's `displayName` is now an empty string.
- **Expected:** Either reject (`@NotBlank` / `@Size(min=1)`) or document that empty display name is permitted. Similar to F-USERS-008, a strict API would 400. (Note: the spec only says "max 255 chars" and "optional", so this is technically per-spec — flagging as a likely UX bug.)
- **Notes:** Combined with the password's `min=8` constraint, the asymmetric strictness of the two fields is suspicious.

## Endpoints with no findings

- `GET /api/users/me` happy path — 200, body `{"id","displayName"}`, `Content-Type: application/json` (matches docs).
- `GET /api/users/me` with no/garbage/expired/wrong-scheme `Authorization` — all 401 (status correct; body shape issue tracked separately as F-USERS-006).
- `GET /api/users/{id}` happy path — 200, public read works without `Authorization`, `Content-Type: application/json` (matches docs).
- `GET /api/users/{id}` with a well-formed but unknown UUID — 404 with project-standard error body (matches expectation).
- `PUT /api/users/{id}` happy path — 200, body matches docs.
- `PUT /api/users/{id}` without `Authorization` — 401 (status correct; body shape issue tracked as F-USERS-006).
- `PUT /api/users/{id}` as a different authenticated user — 403 with project-standard error body (correct ownership enforcement via `@ownership.isSelf`).
- `PUT /api/users/{id}` with `displayName` longer than 255 chars — 400 with the configured Bean Validation message.
- `PUT /api/users/{id}` with `password` shorter than 8 chars — 400 with the configured Bean Validation message.
- `OPTIONS /api/users/me` — 200 with `Allow: GET,HEAD,PUT,OPTIONS` (correct).
- `HEAD /api/users/me` — 200 (correct).

---

## Verification pass

| Finding ID | Status | Note |
|---|---|---|
| F-USERS-001 | CONFIRMED | DELETE /api/users/me, PATCH and POST /api/users/{id} all reproduced 500 with the generic "An unexpected error occurred" body. |
| F-USERS-002 | CONFIRMED | GET and PUT on `/api/users/not-a-uuid` both return 500. |
| F-USERS-003 | CONFIRMED | `{not json}` and an empty body on PUT both yield 500. |
| F-USERS-004 | CONFIRMED | Missing CT, `text/plain`, and `application/xml` all return 500 instead of 415. |
| F-USERS-005 | CONFIRMED | `Accept: text/html` and `Accept: application/xml` both return 401 (empty body) with `Accept: application/json,...` echo header. |
| F-USERS-006 | CONFIRMED | Anonymous GET /api/users/me and PUT /api/users/{id} both return 401 with `Content-Length: 0` and no `Content-Type`. |
| F-USERS-007 | CONFIRMED | PUT /{nonexistent UUID} as a second authenticated user returns 403 "Access denied", while GET /{same UUID} returns 404 "User not found". The two endpoints disagree as described. |
| F-USERS-008 | CONFIRMED | `{"displayName": 123}` is silently coerced to `"123"` and persisted (HTTP 200). |
| F-USERS-009 | CONFIRMED | PUT with `{}` returns 200 and leaves the resource unchanged — partial update on a PUT verb. |
| F-USERS-010 | CONFIRMED | Unknown fields `email` and `admin` are silently dropped, response 200 with only the legal fields applied. |
| F-USERS-011 | CONFIRMED | Both `GET /api/users` and `GET /api/users/` return 401 with empty body. |
| F-USERS-012 | CONFIRMED | `{"displayName":""}` returns 200 and persists an empty display name. |

### Additional findings from verification

- **F-USERS-013 (High, validation):** Non-object JSON top-level values on `PUT /api/users/{id}` return 500 instead of 400. Reproduce with `-d '[1,2,3]'`, `-d '"hello"'`, or `-d 'null'`. Same root cause as F-USERS-003 — `HttpMessageNotReadableException` is unmapped.
- **F-USERS-014 (High, routing/error-shape):** Unmapped sub-paths under an existing controller route return 500 instead of 404. Reproduce: `GET /api/users/me/` (trailing slash) and `GET /api/users/me/extra` both yield 500 with the generic body. Spring's `NoResourceFoundException` (or `NoHandlerFoundException`) is being caught by the catch-all advice. `GET /api/Users/me` (capital U) also produces 500 for the same reason. This is a different surface from F-USERS-001 (verb mismatch) but the same broken-mapping root cause.
- **F-USERS-015 (Low, validation):** `PUT /api/users/{id}` with `{"displayName":null,"password":null}` returns 200 and clears the display name to the empty string. Explicit-null vs absent fields are not distinguished, and the null displayName is coerced to `""` rather than rejected or left unchanged. Combined with F-USERS-012 this means a client can blank out an account's display name through three different code paths (`""`, `null`, omitted-+empty-PUT after a prior `""`).

Bonus observations (not separate findings):
- TRACE and `FOO` (unknown verb) on `/api/users/me` return 401 because Spring Security runs before the dispatcher; this is expected for protected endpoints and not a bug in itself, but it does mean any "method not allowed" signal is hidden behind auth for unauthenticated callers.
- `Authorization: bearer <token>` (lowercase scheme) returns 401. RFC 7235 says auth scheme tokens are case-insensitive; Spring's `BearerTokenAuthenticationFilter` only matches `Bearer` (PascalCase). Marginal interop bug — not raised as a separate finding because most clients send the canonical form.

### Refuted findings — corrected understanding

- None. All twelve original findings reproduced exactly as described.
