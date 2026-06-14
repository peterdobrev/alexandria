# auth — Endpoint findings

## Endpoints covered
- `POST /api/auth/register` — happy path, validation, conflict, content-type, method handling, header / origin variants, idempotency
- `POST /api/auth/login` — happy path, validation, bad credentials, content-type, method handling, header variants, password-length boundary
- `GET /api/auth/register`, `PUT /api/auth/register`, `PATCH /api/auth/register`, `DELETE /api/auth/register`, `HEAD /api/auth/register`, `OPTIONS /api/auth/register`
- `GET /api/auth/login`, `PUT /api/auth/login`, `PATCH /api/auth/login`, `DELETE /api/auth/login`, `OPTIONS /api/auth/login`
- `POST /api/auth/register/` (trailing-slash variant)

## Findings

### F-AUTH-001: Empty / unparseable JSON body returns 500 instead of 400
- **Endpoint:** `POST /api/auth/register`, `POST /api/auth/login`
- **Severity:** High
- **Category:** server-error
- **Reproduction:**
  ```sh
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' -d ''
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' -d '{not json'
  curl -s -i -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' -d ''
  curl -s -i -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' -d 'garbage'
  ```
- **Observed:** `HTTP/1.1 500`, body `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred","timestamp":"...","path":"/api/auth/..."}`
- **Expected:** `HTTP 400 Bad Request` with a useful message (Spring's `HttpMessageNotReadableException` should be mapped). The current behaviour leaks an "internal" error for what is plain client garbage, and the message is opaque.
- **Notes:** `GlobalExceptionHandler` has no `@ExceptionHandler(HttpMessageNotReadableException.class)`; the catch-all `Exception` handler swallows it and returns 500. Same issue affects every JSON endpoint in the API.

### F-AUTH-002: JSON-type mismatch on a field returns 500 instead of 400
- **Endpoint:** `POST /api/auth/register`, `POST /api/auth/login`
- **Severity:** High
- **Category:** server-error / validation
- **Reproduction:**
  ```sh
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"email":123,"password":true,"displayName":[]}'
  curl -s -i -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":42,"password":[1,2,3]}'
  ```
- **Observed:** `HTTP 500` with the generic `An unexpected error occurred` body.
- **Expected:** `HTTP 400` with a message identifying the offending field (Jackson `MismatchedInputException` / `InvalidFormatException`).
- **Notes:** Same root cause as F-AUTH-001 — no handler for `HttpMessageNotReadableException`.

### F-AUTH-003: Missing or wrong Content-Type returns 500 instead of 415
- **Endpoint:** `POST /api/auth/register`, `POST /api/auth/login`
- **Severity:** High
- **Category:** error-shape / server-error
- **Reproduction:**
  ```sh
  # missing Content-Type
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -d '{"email":"a@b.com","password":"Password1!","displayName":"X"}'
  # text/plain
  curl -s -i -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: text/plain' -d '{"email":"x@y.com","password":"x"}'
  # application/xml
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/xml' -d '<root/>'
  ```
- **Observed:** `HTTP 500` `{"message":"An unexpected error occurred", ...}`
- **Expected:** `HTTP 415 Unsupported Media Type` (or `400`). Spring raises `HttpMediaTypeNotSupportedException`; the global handler falls through to the catch-all 500.
- **Notes:** RFC 7231 §6.5.13. Content-type-related faults must not surface as 500.

### F-AUTH-004: Wrong HTTP method on `/api/auth/{register,login}` returns 401 instead of 405
- **Endpoint:** `GET|PUT|PATCH|DELETE|HEAD /api/auth/register`, `GET|PUT|PATCH|DELETE /api/auth/login`
- **Severity:** Medium
- **Category:** method
- **Reproduction:**
  ```sh
  for M in GET PUT PATCH DELETE HEAD; do
    curl -s -o /dev/null -w "$M /register=%{http_code}\n" -X $M http://localhost:8080/api/auth/register
  done
  for M in GET PUT PATCH DELETE; do
    curl -s -o /dev/null -w "$M /login=%{http_code}\n" -X $M http://localhost:8080/api/auth/login
  done
  ```
- **Observed:** All return `HTTP 401` with an empty body (no JSON, no `WWW-Authenticate`, no `Allow` header).
- **Expected:** `HTTP 405 Method Not Allowed` with an `Allow: POST` header (RFC 7231 §6.5.5). Currently the request is denied at the security filter (only POST is `permitAll()`'d on `/api/auth/**`) so Spring MVC never gets the chance to emit 405.
- **Notes:** Cause is in `SecurityConfig#securityFilterChain`: `requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()` then `anyRequest().authenticated()`. Permit all methods on `/api/auth/**` (or explicitly forbid them) and let MVC produce the 405. Bonus: this 401 is also missing the standard JSON error shape.

### F-AUTH-005: Trailing-slash variant returns 500 instead of 404
- **Endpoint:** `POST /api/auth/register/`
- **Severity:** Medium
- **Category:** server-error / rest-best-practice
- **Reproduction:**
  ```sh
  curl -s -i -X POST 'http://localhost:8080/api/auth/register/' \
    -H 'Content-Type: application/json' \
    -d '{"email":"ts@example.com","password":"Password1!","displayName":"T"}'
  ```
- **Observed:** `HTTP 500` `{"status":500,"error":"Internal Server Error","message":"An unexpected error occurred","path":"/api/auth/register/"}`.
- **Expected:** `HTTP 404` (or 308 redirect to the canonical no-slash form). 500 leaks an unexpected NoResourceFoundException through the catch-all.
- **Notes:** Add a `@ExceptionHandler(NoResourceFoundException.class)` (Spring 6.1+) in `GlobalExceptionHandler`, or configure `spring.mvc.use-suffix-pattern` / a redirector for trailing slashes. Affects every non-existent path, not just this one.

### F-AUTH-006: Public endpoint rejects request when caller sends a malformed Bearer token
- **Endpoint:** `POST /api/auth/register`
- **Severity:** High
- **Category:** auth
- **Reproduction:**
  ```sh
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4QHkiLCJleHAiOjE2MDB9.bad' \
    -d '{"email":"new@example.com","password":"Password1!","displayName":"X"}'
  ```
- **Observed:** `HTTP 401`, empty body. The request never reaches the controller even though `/api/auth/register` is a `permitAll()` public endpoint.
- **Expected:** The token should be ignored and the public endpoint should respond as for an anonymous request (`201` or `400`/`409` based on payload).
- **Notes:** `JwtAuthenticationFilter` likely throws on a bad signature/expired token instead of setting no `Authentication` and continuing. A user that already has a stale/expired JWT in their browser (typical for SPA flows) cannot register a new account or refresh their session. Compare with the empty `Authorization: Bearer ` case (which is silently ignored and works fine) and the `Authorization: foo bar` case (also ignored). The filter must tolerate malformed/expired Bearer tokens for `permitAll()` paths.

### F-AUTH-007: 401 responses use a different (empty) body shape than other errors
- **Endpoint:** all `/api/auth/**` paths whose 401 is produced by the security filter (wrong method, malformed Bearer, etc.)
- **Severity:** Medium
- **Category:** error-shape
- **Reproduction:**
  ```sh
  curl -s -i -X PUT http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"email":"a@b.com","password":"Password1!","displayName":"AB"}'
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer not.a.real.jwt' \
    -d '{"email":"x@y.com","password":"Password1!","displayName":"XY"}'
  ```
- **Observed:** `HTTP 401` with `Content-Length: 0` and no `Content-Type`. No JSON, no `WWW-Authenticate` header.
- **Expected:** Consistent `ErrorResponse` body (`status`, `error`, `message`, `timestamp`, `path`) — same shape produced for credential failures (`POST /api/auth/login` with wrong password returns the JSON body). Should also include `WWW-Authenticate: Bearer` per RFC 6750.
- **Notes:** `SecurityConfig` registers `authenticationEntryPoint((_, res, _) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))`, which produces the empty body. Replace with an entry point that writes the project `ErrorResponse` JSON.

### F-AUTH-008: `register` does not return a `Location` header for the new user resource
- **Endpoint:** `POST /api/auth/register`
- **Severity:** Low
- **Category:** rest-best-practice
- **Reproduction:**
  ```sh
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"email":"loc@example.com","password":"Password1!","displayName":"Loc"}'
  ```
- **Observed:** `HTTP 201` with `{"token": "..."}` and no `Location` response header.
- **Expected:** `HTTP 201` should be accompanied by a `Location: /api/users/{id}` header pointing at the created resource (RFC 7231 §6.3.2). The created user's id is currently not exposed in the response either, so the client cannot easily call `GET /api/users/{id}`.
- **Notes:** Use `ResponseEntity.created(URI.create("/api/users/" + user.getId())).body(...)`; consider also returning the user id in the body.

### F-AUTH-009: Login is case-sensitive on email; register normalises to lower-case (asymmetry)
- **Endpoint:** `POST /api/auth/login` vs `POST /api/auth/register`
- **Severity:** Low
- **Category:** other / rest-best-practice
- **Reproduction:**
  ```sh
  EMAIL=audit.auth.1781350284@example.com
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$(echo $EMAIL | tr a-z A-Z)\",\"password\":\"Password1!\"}"
  ```
- **Observed:** Mixed/upper-case email returns `HTTP 401 Invalid credentials` even though the same email registered (lower-case).
- **Expected:** Email is treated as case-insensitive in practice (RFC 5321 / industry convention). Either normalise email to lower-case on both register and login, or document explicitly that the local part is case-sensitive.
- **Notes:** In production this typically results in user-confusion bug reports ("I registered as Foo@Bar.com but it says my password is wrong"). Recommend lower-casing in `RegisterRequest`/`LoginRequest` mappers.

### F-AUTH-010: OpenAPI advertises 200 for register; controller returns 201
- **Endpoint:** `POST /api/auth/register`
- **Severity:** Low
- **Category:** rest-best-practice / error-shape
- **Reproduction:**
  ```sh
  jq '.paths."/api/auth/register".post.responses' /tmp/alexandria-openapi.json
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"email":"verify@example.com","password":"Password1!","displayName":"V"}'
  ```
- **Observed:** OpenAPI declares only `200 OK`; runtime returns `HTTP 201 Created`. (Human-written `docs/rest-api.md` correctly says 201.)
- **Expected:** OpenAPI should match runtime — declare `201` (and document `400`/`409`).
- **Notes:** Add explicit `@ApiResponses` / springdoc annotations on the controller method, or configure `springdoc` to derive the status from `ResponseEntity.status(...)`. Same gap likely exists across other controllers.

### F-AUTH-011: Validation messages concatenated into a single string; no per-field structure
- **Endpoint:** `POST /api/auth/register`, `POST /api/auth/login`
- **Severity:** Low
- **Category:** error-shape / rest-best-practice
- **Reproduction:**
  ```sh
  curl -s -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' -d '{"email":"","password":"","displayName":""}'
  ```
- **Observed:** `{"message":"Email is required, Password must be at least 8 characters, Display name is required, Password is required, Display name must be between 2 and 50 characters", ...}` — flat comma-joined string, includes duplicates ("Password is required" + "Password must be at least 8 characters") and no field name.
- **Expected:** A structured per-field list (RFC 7807 `errors[]` or a `fields: { email: "...", password: "..." }` map). The current shape is hard to render in a UI.
- **Notes:** The `errorCode` field that already exists on `ConflictException` shows the project values structured codes; that pattern should extend to validation. Also consider a stable code per constraint (e.g., `email.required`, `password.minLength`).

### F-AUTH-012: `register` accepts and silently drops unknown JSON properties
- **Endpoint:** `POST /api/auth/register`
- **Severity:** Low
- **Category:** validation / rest-best-practice
- **Reproduction:**
  ```sh
  curl -s -i -X POST http://localhost:8080/api/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"email":"unk@example.com","password":"Password1!","displayName":"X","role":"ADMIN","admin":true}'
  ```
- **Observed:** `HTTP 201` with token; `role`/`admin` are silently ignored.
- **Expected:** Either a `400` for unknown properties (strict mode) or, at minimum, document the lenient behaviour. Silent acceptance of `role: "ADMIN"` is a privilege-escalation foot-gun even though the field is unmapped — a future refactor that adds a `role` field to `RegisterRequest` could become exploitable instantly.
- **Notes:** Set `spring.jackson.deserialization.fail-on-unknown-properties=true` (or `@JsonIgnoreProperties(ignoreUnknown = false)` per DTO).

## Endpoints with no findings
- `POST /api/auth/register` happy path: `HTTP 201`, JSON body `{"token":"..."}`, `Content-Type: application/json` — matches `docs/rest-api.md`.
- `POST /api/auth/login` happy path: `HTTP 200`, JSON body `{"token":"..."}`, `Content-Type: application/json` — matches docs.
- `POST /api/auth/register` with duplicate email: `HTTP 409 Conflict` with the project `ErrorResponse` body — correct.
- `POST /api/auth/login` with non-existent email or wrong password: `HTTP 401 Invalid credentials` (constant-time, identical message for both branches per `AuthService#login`'s dummy-hash trick) — correct and good practice.
- `POST /api/auth/register` field-level validation (missing/blank/too-short/too-long fields, invalid email, short password, oversize-bcrypt password ≥73 bytes): each returns `HTTP 400` with the correct human-readable message; 72-byte boundary accepted — correct.
- `POST /api/auth/login` field-level validation (missing/blank email and password, invalid email format): all return `HTTP 400` correctly.
- CORS preflight from the configured allowed origin: `OPTIONS /api/auth/register` with `Origin: http://localhost:4200` returns `HTTP 200` with the expected `Access-Control-Allow-*` headers.

---

## Verification pass

| Finding ID | Status | Note |
|---|---|---|
| F-AUTH-001 | CONFIRMED | Empty body and `{not json` both return HTTP 500 with generic "An unexpected error occurred" body on register and login. |
| F-AUTH-002 | CONFIRMED | Type-mismatched fields (`{"email":123,...}`) return HTTP 500 on both endpoints. |
| F-AUTH-003 | CONFIRMED | Missing Content-Type, `text/plain`, and `application/xml` all return HTTP 500. |
| F-AUTH-004 | CONFIRMED | GET/PUT/PATCH/DELETE/HEAD on `/api/auth/register` and `/api/auth/login` return HTTP 401, no `Allow` header, empty body. |
| F-AUTH-005 | CONFIRMED | `POST /api/auth/register/` returns HTTP 500 with the catch-all body. |
| F-AUTH-006 | CONFIRMED | Malformed Bearer token on `/api/auth/register` returns HTTP 401 empty body. Valid Bearer token reaches controller (HTTP 201 observed); empty `Bearer ` and `foo bar` are tolerated and the request proceeds — only malformed JWT strings break the public endpoint, exactly as described. |
| F-AUTH-007 | CONFIRMED | `PUT /api/auth/register` and Bearer-malformed cases return 401 with `Content-Length: 0`, no `Content-Type`, no `WWW-Authenticate`. |
| F-AUTH-008 | CONFIRMED | `POST /api/auth/register` happy path returns 201 with `{"token":"..."}` only — no `Location` header, no user id in body. |
| F-AUTH-009 | CONFIRMED | Registered `case.<ts>@example.com` lower-case, login with upper-case form returns 401. |
| F-AUTH-010 | CONFIRMED | `/v3/api-docs` declares only `200` for `POST /api/auth/register`; runtime returns 201. |
| F-AUTH-011 | CONFIRMED | All blank fields produce a single comma-joined `message` string with duplicate "Password is required"/"Password must be at least 8 characters" and no field names. |
| F-AUTH-012 | CONFIRMED | Register with `role: "ADMIN"`, `admin: true` and a valid `displayName` returns 201 silently dropping the unknown fields. |

### Additional findings from verification

- **F-AUTH-013 (NEW): Top-level JSON array body returns 500 instead of 400.** `POST /api/auth/register` with body `[{"email":...}]` returns HTTP 500 (same generic catch-all body). Same root cause as F-AUTH-001 (no `HttpMessageNotReadableException` handler), but worth listing as a separate symptom because RFC-shape clients sometimes wrap a single resource in an array.

- **F-AUTH-014 (NEW): Duplicate JSON keys are silently accepted; the last value wins.** `POST /api/auth/register` with `{"email":"dup@example.com","email":"dup2@example.com",...}` returns HTTP 201 and registers the user as `dup2@example.com`. The server does not reject ambiguous payloads (Jackson default = lenient). Severity: Low / validation. RFC 8259 §4 says behavior is "unpredictable" for duplicate keys, but for an auth endpoint silently picking the second value can mask request-smuggling / parser-differential attacks; recommend `DeserializationFeature.FAIL_ON_DUPLICATE_KEYS` (`spring.jackson.deserialization.fail-on-duplicate-keys=true`).

- **F-AUTH-015 (NEW): Oversized request body returns 500 instead of 413.** `POST /api/auth/register` with a ~100 KB raw payload returns HTTP 500 generic body instead of `413 Payload Too Large` or `400 Bad Request`. Severity: Low / server-error. Tomcat / Spring should be configured with a `max-http-form-post-size` or the `HttpMessageNotReadableException` handler from F-AUTH-001 will cover most parse-failure variants of this.

- **F-AUTH-016 (NEW): `OPTIONS /api/auth/register` without an `Origin` header returns 401 empty body.** The CORS filter only short-circuits when `Origin` is present; otherwise the security filter rejects with the same broken 401 shape as F-AUTH-007. RFC 7231 §4.3.7 says OPTIONS should be supported by every resource. Severity: Low. (Mentioned alongside the existing positive-case OPTIONS observation in "Endpoints with no findings".)

### Refuted findings — corrected understanding

- none — every finding F-AUTH-001 through F-AUTH-012 reproduced exactly as described.

