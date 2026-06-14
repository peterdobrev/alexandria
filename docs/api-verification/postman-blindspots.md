# Postman Collection Blindspots

The collection (`docs/postman/Alexandria-API.postman_collection.json`) was audited against the
code changes described in the API surface changes summary. Every finding below is a concrete
discrepancy between what the collection currently contains and what the production code now does.

---

## AuthResponse — Register User

**Request:** Auth / Register User
**Issue:** The collection has no test script that captures or validates the new `AuthResponse`
fields. The response body now contains four fields (`userId`, `token`, `tokenType`, `expiresIn`)
but the collection does not capture `userId`, `tokenType`, or `expiresIn` in a post-response
script, and does not assert that these fields are present.
**Expected:** A post-response test script that:
1. Asserts HTTP 201.
2. Saves `pm.environment.set("authToken", pm.response.json().token)`.
3. Saves `pm.environment.set("currentUserId", pm.response.json().userId)` (needed by other
   requests, e.g. Update User).
4. Asserts `pm.response.json().tokenType === "Bearer"`.
5. Asserts `pm.response.json().expiresIn` is a positive number.
**Current:** No test script exists in the collection for this request.

---

## AuthResponse — Login User

**Request:** Auth / Login User
**Issue:** Same gap as Register: no test script captures or validates the new `AuthResponse`
fields. `userId`, `tokenType`, and `expiresIn` are new fields that a consumer must know about, and
the only currently documented field is `token` (implicitly, because `authToken` is described in the
collection variable).
**Expected:** A post-response test script that:
1. Asserts HTTP 200.
2. Saves `pm.environment.set("authToken", pm.response.json().token)`.
3. Saves `pm.environment.set("currentUserId", pm.response.json().userId)`.
4. Asserts `pm.response.json().tokenType === "Bearer"`.
5. Asserts `pm.response.json().expiresIn` is a positive number.
**Current:** No test script exists in the collection for this request.

---

## POST /api/auth/register — Location Header Not Documented

**Request:** Auth / Register User
**Issue:** The endpoint now returns a `Location` header pointing to the newly created user
(`<context-path>/api/users/{userId}`). The collection has no assertion or documentation note for
this header.
**Expected:** A test script assertion such as
`pm.expect(pm.response.headers.get("Location")).to.match(/\/api\/users\/[0-9a-f-]{36}$/)`.
**Current:** No mention of the `Location` header in the request or its tests.

---

## POST /api/documents — Missing Auth Guard Documentation

**Request:** Documents / Upload File Document
**Issue:** The endpoint now has `@PreAuthorize("isAuthenticated()")`. The collection already sends
`Authorization: Bearer {{authToken}}`, so the header is present. However, there is no test case
(separate request or test script) that verifies the endpoint correctly rejects an unauthenticated
call with `401 Unauthorized` and a structured `ErrorResponse` JSON body (as returned by
`JsonAuthenticationEntryPoint`).
**Expected:** A negative-path request or test assertion covering the unauthenticated `401` case
with body `{"status":401,"error":"Unauthorized","message":"Authentication required",...}`.
**Current:** Only the happy-path authenticated request is present; no 401 coverage.

---

## POST /api/documents/article — Missing Auth Guard Documentation

**Request:** Documents / Create Article Document
**Issue:** Same as Upload File Document above — `@PreAuthorize("isAuthenticated()")` was added.
The authorization header is present in the collection but there is no negative-path request or test
that verifies the `401` response for an unauthenticated caller.
**Expected:** Negative-path test or request demonstrating the `401` response.
**Current:** Only the happy-path authenticated request is present; no 401 coverage.

---

## POST /api/documents/article — Location Header Not Documented

**Request:** Documents / Create Article Document
**Issue:** The endpoint now returns a `Location` header with the absolute URL of the created
document. The collection has no assertion or documentation note for this header.
**Expected:** A test script assertion such as
`pm.expect(pm.response.headers.get("Location")).to.match(/\/api\/documents\/[0-9a-f-]{36}$/)`.
**Current:** No mention of the `Location` header.

---

## POST /api/documents (multipart) — Location Header Not Documented

**Request:** Documents / Upload File Document
**Issue:** The endpoint now returns an absolute `Location` header. The collection has no test or
documentation note for this header.
**Expected:** A test script assertion validating the `Location` header format.
**Current:** No mention of the `Location` header.

---

## POST /api/documents/{documentId}/comments — Auth Enforcement Not Documented

**Request:** Comments / Add Comment to Document
**Issue:** The endpoint now has `@PreAuthorize("isAuthenticated()")`. A previously unauthenticated
call would have succeeded; it now returns `401`. The collection has no test that exercises the
unauthenticated path. Additionally, the response status changed to `201 Created` (was not
explicitly `201` before), and a `Location` header was added.
**Expected:**
1. A test script asserting HTTP 201.
2. A test script asserting the `Location` header is present and matches
   `/api/documents/{documentId}/comments/{commentId}`.
3. A negative-path request verifying `401` for unauthenticated callers.
**Current:** No test script on this request; no `Location` header documented; no 401 coverage.

---

## POST /api/reading-lists/{id}/items — Location Header Not Documented

**Request:** Reading Lists / Add Item to Reading List
**Issue:** The response now includes a `Location` header pointing to the added item
(`<current-request-path>/{documentId}`). The collection has no assertion or documentation note for
this header.
**Expected:** A test script assertion such as
`pm.expect(pm.response.headers.get("Location")).to.include("/items/")`.
**Current:** No mention of the `Location` header.

---

## CreateArticleRequest — `type` Field Undocumented Size Constraint

**Request:** Documents / Create Article Document
**Issue:** `CreateArticleRequest.type` now has `@Size(max = 50)`. The example body in the
collection sets `"type": "ARTICLE"` which is well within the limit and is fine. However, there is
no negative-path request that demonstrates the `400 Bad Request` response when `type` exceeds 50
characters. The new `ConstraintViolationException` handler returns a structured `ErrorResponse`
JSON body for this case which is not documented anywhere in the collection.
**Expected:** A negative-path request with `type` longer than 50 characters, expecting HTTP 400
with a structured `ErrorResponse` body.
**Current:** Only the happy-path request exists.

---

## CreateArticleRequest — `body` Field Undocumented Size Constraint

**Request:** Documents / Create Article Document
**Issue:** `CreateArticleRequest.body` now has `@Size(max = 500_000)`. No negative-path request
exercises this constraint. A body exceeding 500,000 characters should produce HTTP 400 with
`ErrorResponse`.
**Expected:** Documentation note in the request description and, ideally, a negative-path test.
**Current:** Not mentioned.

---

## CreateArticleRequest / CreateDocumentRequest — `categoryIds` Size Constraint

**Requests:** Documents / Create Article Document, Documents / Upload File Document
**Issue:** Both `CreateArticleRequest.categoryIds` and `CreateDocumentRequest.categoryIds` now have
`@Size(max = 20)`. No negative-path request exercises this constraint.
**Expected:** A test or description note covering the 400 response when more than 20 category IDs
are submitted.
**Current:** Not mentioned in either request.

---

## UpdateUserRequest — `displayName` and `password` Blank Validation

**Request:** Users / Update User
**Issue:** Both `displayName` and `password` fields now carry `@NullOrNotBlank`. Submitting either
field as an empty string (`""`) or a whitespace-only string must return HTTP 400. The collection
has no negative-path request covering this case, and the constraint behaviour (null is allowed,
blank is not) is not documented.
**Expected:** A negative-path request with `"displayName": ""` or `"password": " "` expecting HTTP
400, and a description note clarifying that `null` omits the field update while `""` is rejected.
**Current:** Only the happy-path request with valid values is present.

---

## New Structured Error Response Not Covered by Any Test

**All requests**
**Issue:** The `GlobalExceptionHandler` now returns a structured `ErrorResponse` JSON body for a
wide range of errors that previously returned plain HTTP status codes or no body:
- `401 Unauthorized` — `{"status":401,"error":"Unauthorized","message":"Authentication required",...}`
- `405 Method Not Allowed` with `Allow` header
- `415 Unsupported Media Type` with `Accept` header
- `400 Bad Request` for malformed JSON (`"Malformed request body"`), type mismatches
  (`"Invalid value '<v>' for parameter '<name>'"`) and missing parameters
  (`"Required parameter '<name>' is missing"`)
- `404 Not Found` for unknown paths (`"Resource not found"`)
- `400 Bad Request` for invalid multipart requests

None of these error shapes appear in the collection as separate requests or test script assertions.
The collection documents no error response bodies at all beyond the implicit `40x` status codes
that some requests might encounter.
**Expected:** At minimum, one representative negative-path request per error category, or test
scripts that assert the `ErrorResponse` shape (`status`, `error`, `message`, `timestamp`, `path`
fields) on error responses.
**Current:** No error response documentation in the collection.

---

## Unauthenticated Error Response Shape Changed

**All requests with `Authorization` header**
**Issue:** Previously, an unauthenticated request (missing or invalid token) returned a plain
`SC_UNAUTHORIZED` with no body. Now `JsonAuthenticationEntryPoint` returns a structured
`ErrorResponse` JSON body with `Content-Type: application/json`. Any client-side test that checks
for an empty body on a `401` response will now fail.
**Expected:** Test scripts for protected endpoints should assert the `401` body shape:
```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication required",
  "timestamp": "<ISO instant>",
  "path": "<request path>"
}
```
**Current:** No test scripts exist in the Postman collection to assert `401` response bodies.

---

## JWT Token — issuer/aud Claims Not Mentioned

**Request:** Auth / Login User, Auth / Register User (and implicitly all protected requests)
**Issue:** Tokens issued by the server now embed `iss`, `aud`, and `jti` claims and the server
validates them on every request. Any token stored in `{{authToken}}` that was issued before this
change (i.e., by an older running instance) will be rejected with `401 "Invalid or expired token"`.
There is no note in the collection variable description or in the Auth requests warning consumers
that previously issued tokens are invalid and a fresh login is required after a server upgrade.
**Expected:** A description note on the `authToken` collection variable or in the Auth folder
description stating that tokens contain `iss`/`aud` claims and must be re-issued when switching
between server versions.
**Current:** The `authToken` variable description only says "JWT Bearer token — populate after
calling POST /api/auth/login".

---

## JWT Token — Error Message Changes

**All requests with `Authorization` header**
**Issue:** The error messages returned for JWT failures changed:
- Expired token: was `"JWT token is invalid or expired"`, now `"JWT token has expired"`
- Invalid/malformed token: was `"JWT token is invalid or expired"`, now `"JWT token is invalid or malformed"`

Any test scripts that match the exact old error message string will now produce false failures.
**Expected:** Test scripts (if any) asserting JWT error messages must use the new strings.
**Current:** No test scripts exist in the collection, so this is a latent risk rather than an
active failure — but it is a blindspot for anyone adding tests based on the old message strings.

---

## Authorization Header — Case-Insensitive Bearer Not Documented

**All requests with `Authorization` header**
**Issue:** The JWT filter now accepts `Bearer`, `bearer`, and `BEARER` case-insensitively, and
strips whitespace from the token value. This relaxed parsing is not documented anywhere in the
collection, which could mislead consumers into believing only `Bearer` (title case) is accepted.
**Expected:** A note in the collection or Auth folder description that the `Authorization` header
value is case-insensitive for the scheme prefix.
**Current:** All example requests use `Bearer {{authToken}}` with no mention of the relaxed
parsing.

---

## Security Response Headers Not Tested

**All requests**
**Issue:** All API responses now include three new security headers:
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- `Referrer-Policy: no-referrer`
- `Content-Security-Policy: default-src 'none'`

No test scripts in the collection assert these headers. Clients that inspect response headers
(e.g., proxy configurations, browser extensions, automated tests) will encounter headers that
were not present before.
**Expected:** Test scripts on at least one representative request asserting the presence and value
of these three headers.
**Current:** No test scripts exist in the collection for response header validation.

---

## PATCH Method Added to CORS — Not Present in Collection

**All endpoints**
**Issue:** `PATCH` was added to the CORS allowed methods. The collection has no `PATCH` requests.
If the API exposes any `PATCH` endpoint (e.g., a partial update route that may be added in the
future), the collection will be missing those requests. More critically, there is no pre-flight
OPTIONS test that verifies `PATCH` appears in the `Allow` / `Access-Control-Allow-Methods`
response header.
**Expected:** Either a note in the collection description, or an OPTIONS pre-flight request against
an endpoint confirming `PATCH` is included in `Access-Control-Allow-Methods`.
**Current:** No `PATCH` requests and no OPTIONS pre-flight request in the collection.

---

## Pagination Size Cap — Not Documented or Tested

**Requests with pagination:** List Documents, Get Reading Lists, Get Comments for Document,
Get Document Recommendations
**Issue:** A new `PaginationConfig` caps `size` at `100`. Requests with `size > 100` are silently
capped to `100`. The collection uses `size=20` and `size=10` in examples (both within the limit),
but there is no documentation note about the cap and no test that sends `size=101` and verifies
the response contains at most 100 items.
**Expected:** A description note on paginated requests stating the maximum page size is 100, and
optionally a test asserting that `size > 100` is silently capped.
**Current:** No mention of the page size cap anywhere in the collection.

---

## Privacy / Access Control — 404 vs 403 Behaviour Change Not Documented

**Requests:** Comments / Get Comments for Document, Reading Lists / Add Item to Reading List
**Issue:** Two access control behaviours changed:
1. Accessing comments on a private document as a non-owner now returns `404 Not Found` instead
   of `403 Forbidden`.
2. Adding a private document to a reading list as a non-owner now returns `404 Not Found` instead
   of succeeding.

The collection has no documentation of these privacy-masking rules and no test that exercises
either scenario. A consumer who previously expected `403` for these cases will be surprised by
`404`.
**Expected:** Description notes on the relevant requests explaining the privacy-masking behaviour,
and negative-path requests covering both scenarios.
**Current:** No coverage of these access-control scenarios in the collection.

---

## ReadingListResponse — Private Document Filtering Not Documented

**Request:** Reading Lists / Get Reading List by ID
**Issue:** `ReadingListResponse` now silently omits private documents not owned by the reading list
owner from the `items` array. A consumer who adds a private foreign document to a reading list and
later fetches that list will observe fewer items than were added, with no error or explanation.
The collection has no note about this filtering behaviour.
**Expected:** A description note on the Get Reading List by ID request explaining that items
containing private documents owned by another user are silently filtered from the response.
**Current:** No mention of this filtering behaviour.

---

## OwnershipService 404 vs 403 for Non-Existent Resources

**Requests:** Any request that uses `@PreAuthorize` with `@ownership.*` SpEL expressions
(e.g., Update Document Metadata, Delete Document, Update Reading List, Delete Reading List,
Get Reading List by ID, Add/Remove Item from Reading List, Delete Comment)
**Issue:** When the resource referenced in the path variable does not exist, `OwnershipService` now
throws `404 Not Found` instead of returning `false` (which previously produced `403 Forbidden`).
The collection has no test that exercises a request against a non-existent resource ID and asserts
the specific status code and error body.
**Expected:** Negative-path requests using non-existent UUIDs asserting HTTP `404` with
`{"message":"Resource not found",...}` body.
**Current:** Only happy-path requests with valid-looking (but placeholder) UUIDs are present; no
coverage of the 404-from-ownership scenario.

---

## `IllegalArgumentException` Message Now Hidden

**Requests:** Documents / List Documents (sort validation path)
**Issue:** The `GlobalExceptionHandler` previously leaked `ex.getMessage()` to the client for
`IllegalArgumentException`. It now always returns the fixed string `"Invalid request"`. Any test
script that asserted a specific message from an `IllegalArgumentException` (e.g., checking for
`"Sort field not allowed: ..."` in the response body when an invalid sort field is submitted) will
now fail.
**Expected:** Test scripts (if any) asserting `IllegalArgumentException` responses must only check
for the generic message `"Invalid request"`.
**Current:** No test scripts in the collection, but this is a latent trap for anyone who adds tests
expecting the old leaked message. The collection has no negative-path request for invalid sort
parameters at all.

---

## `InvalidTokenException` / `UsernameNotFoundException` — New 401 Handlers Not Tested

**All requests with `Authorization` header**
**Issue:** Two new `@ExceptionHandler` methods were added:
- `InvalidTokenException` → HTTP `401`, message `"Invalid or expired token"`
- `UsernameNotFoundException` → HTTP `401`, message `"Authentication required"`

No request in the collection exercises these paths (e.g., submitting a syntactically valid but
expired/invalid JWT, or a token whose subject email no longer exists in the database).
**Expected:** Negative-path requests covering:
1. An expired/invalid JWT → HTTP 401, `message: "Invalid or expired token"`.
2. A token for a deleted user → HTTP 401, `message: "Authentication required"`.
**Current:** No coverage.
