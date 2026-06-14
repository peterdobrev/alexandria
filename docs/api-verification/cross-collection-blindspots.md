# Cross-Collection & Docs Blindspots

---

## AuthResponse Shape — Register and Login

**Issue:** Both collections and the docs show only `{ "token": "..." }` as the auth response body. The actual response now contains four fields: `userId`, `token`, `tokenType`, and `expiresIn`. Neither collection captures `userId` after registration or login, and neither tests for the new fields.
**Bruno:** Post-response script captures `res.body.token` only (`bru.setEnvVar("authToken", res.body.token)`). `userId`, `tokenType`, and `expiresIn` are silently ignored.
**Postman:** No post-response scripts at all on either Register or Login — `authToken` collection variable is never auto-populated from a live response.
**Docs (rest-api.md):** Both `POST /api/auth/register` and `POST /api/auth/login` show `{ "token": "string" }` as the full response body. The three new fields are not documented.
**Expected:** Docs should show the full shape: `{ "userId": "UUID", "token": "string", "tokenType": "Bearer", "expiresIn": <long> }`. Bruno scripts should optionally capture `userId` (e.g. `bru.setEnvVar("userId", res.body.userId)`). Postman needs post-response scripts equivalent to Bruno's to populate `authToken` automatically.

---

## POST /api/auth/register — Missing Location Header

**Issue:** The register endpoint now returns a `Location` header (`<context-path>/api/users/{userId}`). Neither collection tests for or documents this header, and the docs do not mention it.
**Bruno:** No assertion or capture of the `Location` header.
**Postman:** No assertion or capture of the `Location` header.
**Docs (rest-api.md):** Response section for `POST /api/auth/register` lists only `201 Created` with the body. No `Location` header is mentioned.
**Expected:** Docs should add: `Location` header set to `/api/users/{userId}`. Collections should assert or at minimum capture the header.

---

## Postman — No Auto-Token Scripts on Auth Endpoints

**Issue:** Postman has no post-response (test/event) scripts on either the Register or Login requests. The `authToken` collection variable is defined but never written at runtime, so every other request in the collection that uses `{{authToken}}` will send an empty token unless the user manually pastes a value.
**Bruno:** Both `register-user.bru` and `login-user.bru` have `script:post-response` blocks that write `authToken` to the environment on success.
**Postman:** Neither request has an `event` array or test script. The variable is declared in the collection-level `variable` array with an empty value.
**Docs (rest-api.md):** N/A — this is a collection usability gap, not a docs gap.
**Expected:** Postman's Register and Login requests should each include a test script that sets `pm.collectionVariables.set("authToken", pm.response.json().token)` (and optionally `userId`) on the respective success status codes (201 and 200).

---

## GET /api/documents — Optional Auth Shown as Required

**Issue:** `GET /api/documents` is a public endpoint (anonymous allowed). Both collections send an `Authorization: Bearer {{authToken}}` header unconditionally, making the request look auth-required and causing it to fail (401/empty token) when `authToken` is not yet populated.
**Bruno:** `list-documents.bru` sets `auth: bearer` with `token: {{authToken}}`.
**Postman:** The "List Documents" request includes `Authorization: Bearer {{authToken}}` as a hardcoded header.
**Docs (rest-api.md):** Correctly states `Auth: Optional (anonymous allowed)`.
**Expected:** Both collections should remove the unconditional auth header (or make it conditional/optional). The auth token should only be included when the user actually wants to see their own private documents mixed into results.

---

## GET /api/documents/{id} — Optional Auth Shown as Required

**Issue:** Same as above for the single-document endpoint.
**Bruno:** `get-document-by-id.bru` sets `auth: bearer`.
**Postman:** "Get Document by ID" sends `Authorization: Bearer {{authToken}}`.
**Docs (rest-api.md):** Correctly states `Auth: Optional (anonymous allowed)`.
**Expected:** Auth should be optional (not sent unconditionally) in both collections.

---

## GET /api/documents/{id}/file — Optional Auth Shown as Required

**Issue:** File streaming is public for public documents. Both collections attach a bearer token unconditionally.
**Bruno:** `stream-document-file.bru` sets `auth: bearer`.
**Postman:** "Stream Document File" sends `Authorization: Bearer {{authToken}}`.
**Docs (rest-api.md):** Correctly states `Auth: Optional (anonymous allowed)`.
**Expected:** Auth should be optional in both collections.

---

## POST /api/documents (multipart) — Metadata Part Format Discrepancy vs Docs

**Issue:** The API spec defines the request as multipart with two parts: a binary `file` part and a JSON `metadata` part. Both collections instead use flat `metadata.title`, `metadata.description`, etc. form fields. These may work if the server accepts Spring's parameter binding on flat keys, but they diverge from what the docs describe and what the `@RequestPart` annotation implies.
**Bruno:** `upload-file-document.bru` sends `metadata.title`, `metadata.description`, `metadata.type`, `metadata.categoryIds`, `metadata.visibility` as separate text parts.
**Postman:** "Upload File Document" sends the same flat `metadata.*` keys as `formdata` entries.
**Docs (rest-api.md):** Describes two named parts: `file` (binary) and `metadata` (JSON). Implies `Content-Type: application/json` for the `metadata` part, not flat form fields.
**Expected:** If the server uses `@RequestPart("metadata") CreateDocumentRequest metadata`, the `metadata` part must be a single JSON blob with `Content-Type: application/json`, not flat key-value pairs. Collections should send it accordingly, and the docs should clarify the exact multipart structure (including the `Content-Type` of the `metadata` part).

---

## POST /api/documents (multipart) — Location Header Produces Absolute URL, Docs Say Relative

**Issue:** The code now builds the `Location` header using `ServletUriComponentsBuilder`, producing an absolute URL (e.g. `http://localhost:8080/api/documents/{id}`). The docs describe it as a relative path.
**Bruno:** No assertion on the `Location` header value.
**Postman:** No assertion on the `Location` header value.
**Docs (rest-api.md):** States `Location header set to /api/documents/{id}` — a relative path.
**Expected:** Docs should state that `Location` is an absolute URL, e.g. `http://<host>/api/documents/{id}`.

---

## POST /api/documents/article — Location Header Produces Absolute URL, Docs Say Relative

**Issue:** Same as above — code uses `ServletUriComponentsBuilder.fromCurrentContextPath()` producing an absolute URL.
**Bruno:** No assertion on the `Location` header.
**Postman:** No assertion on the `Location` header.
**Docs (rest-api.md):** States `Location header set to /api/documents/{id}` — a relative path.
**Expected:** Docs should describe an absolute URL for the `Location` header.

---

## POST /api/documents/{documentId}/comments — Missing Location Header in Docs and Collections

**Issue:** The comment creation endpoint now returns a `Location` header (`<current-request-path>/{commentId}`) alongside the `201 Created` response. Neither collection captures or asserts this, and the docs do not mention it.
**Bruno:** `add-comment-to-document.bru` has no assertion or capture for the `Location` header.
**Postman:** "Add Comment to Document" has no assertion or capture for the `Location` header.
**Docs (rest-api.md):** No `Location` header mentioned in the `POST /api/documents/{documentId}/comments` section.
**Expected:** Docs should add a `Location` header note. Collections should at minimum capture the header.

---

## POST /api/reading-lists/{id}/items — Missing Location Header in Docs and Collections

**Issue:** Adding an item to a reading list now returns a `Location` header (`<current-request-path>/{documentId}`) alongside `201 Created`. Neither collection captures it, and the docs omit it.
**Bruno:** `add-item-to-reading-list.bru` has no assertion or capture for the `Location` header.
**Postman:** "Add Item to Reading List" has no assertion or capture for the `Location` header.
**Docs (rest-api.md):** No `Location` header mentioned in the `POST /api/reading-lists/{id}/items` section.
**Expected:** Docs should document the `Location` header. Collections should capture it.

---

## POST /api/documents and POST /api/documents/article — Auth Now Explicitly Required

**Issue:** Both upload endpoints previously had no method-level auth guard. They now carry `@PreAuthorize("isAuthenticated()")`. The docs correctly say `Auth: Authenticated`, but if any test workflow assumed these would silently accept unauthenticated calls (relying only on global security config), that assumption is now incorrect at the method level. Neither collection exercises the unauthenticated path to confirm `401` is returned.
**Bruno:** Sends `auth: bearer` — correct, but no negative test.
**Postman:** Sends `Authorization: Bearer {{authToken}}` — correct, but no negative test.
**Docs (rest-api.md):** Both endpoints correctly state `Auth: Authenticated`. No gap in the description, but no note that this is now enforced at the method level.
**Expected:** Collections should include a negative-case request (no token) to verify `401` is returned. Docs note is informational only.

---

## POST /api/documents/{documentId}/comments — Auth Enforcement Now Explicit

**Issue:** Comments were previously open (no method-level guard). The endpoint now has `@PreAuthorize("isAuthenticated()")`. Both collections send auth tokens correctly, but neither tests the unauthenticated path.
**Bruno:** Sends `auth: bearer` — correct.
**Postman:** Sends `Authorization: Bearer {{authToken}}` — correct.
**Docs (rest-api.md):** States `Auth: Authenticated` — correct.
**Expected:** A negative-case request (no token) should be added to both collections to confirm `401` is returned.

---

## CreateReadingListRequest / UpdateReadingListRequest — Max Length Not Documented

**Issue:** Both DTOs now enforce `@Size(max = 255)` on `name`. The docs only say "required, not blank" for `POST` and "required, not blank" for `PUT`. Neither collection sends a name exceeding 255 chars to verify the constraint.
**Bruno:** Both `create-reading-list.bru` and `update-reading-list.bru` use short names.
**Postman:** Same — both use short names.
**Docs (rest-api.md):** `POST /api/reading-lists` — field table says `required, not blank`; no max length. `PUT /api/reading-lists/{id}` — same.
**Expected:** Docs should add `max 255 chars` to the `name` constraint column for both endpoints.

---

## CreateArticleRequest — Missing Size Constraints in Docs

**Issue:** Three fields in `CreateArticleRequest` have new `@Size` constraints that are absent from the docs:
- `type`: now `@Size(max = 50)`
- `body`: now `@Size(max = 500_000)`
- `categoryIds`: now `@Size(max = 20)`

**Bruno:** `create-article-document.bru` does not test boundary values.
**Postman:** "Create Article Document" does not test boundary values.
**Docs (rest-api.md):** `POST /api/documents/article` field table — `type` lists no max, `body` lists no max, `categoryIds` lists no max.
**Expected:** Docs should add: `type` — max 50 chars; `body` — max 500 000 chars; `categoryIds` — max 20 items.

---

## CreateDocumentRequest (multipart) — categoryIds Max Not Documented

**Issue:** `CreateDocumentRequest.categoryIds` now has `@Size(max = 20)`. This is not mentioned in the docs for the file upload endpoint.
**Bruno:** `upload-file-document.bru` sends a single category ID, no boundary test.
**Postman:** "Upload File Document" sends a single category ID, no boundary test.
**Docs (rest-api.md):** `POST /api/documents` metadata field table — `categoryIds` listed as `optional` with no max size.
**Expected:** Docs should add: `categoryIds` — optional, max 20 items.

---

## UpdateUserRequest — Blank String Rejection Not Documented

**Issue:** `UpdateUserRequest.displayName` and `password` now carry a custom `@NullOrNotBlank` validator. Sending an empty string `""` for either field will return `400 Bad Request`. The docs say "optional" but do not warn that blank strings (as opposed to absent fields) are rejected.
**Bruno:** `update-user.bru` sends non-blank values only.
**Postman:** "Update User" sends non-blank values only.
**Docs (rest-api.md):** `PUT /api/users/{id}` — `displayName` described as "optional, max 255 chars"; `password` described as "optional, 8–255 chars". No mention that `""` (blank string) is rejected.
**Expected:** Docs should note that if a field is provided it must be non-blank (i.e., `null` to omit, or a non-empty value to update). Both fields effectively become "null or non-blank string".

---

## Pagination Page Size Cap — Not Documented

**Issue:** A `PaginationConfig` now caps the `size` query parameter at 100 on all pageable endpoints. Requests with `size > 100` are silently reduced to 100. This behaviour is not documented anywhere in rest-api.md, nor is it demonstrated in either collection.
**Bruno:** All paginated requests use `size: 20` (well within the cap), so the cap is never exercised.
**Postman:** Same — `size=20` used throughout.
**Docs (rest-api.md):** No mention of a maximum page size on any paginated endpoint.
**Expected:** Docs should note the maximum `size` value (100) for all paginated endpoints. A note in the general query-parameter table or a top-level section would suffice.

---

## Security Response Headers — Not Documented

**Issue:** All responses now include three new security headers: `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `Referrer-Policy: no-referrer`, and `Content-Security-Policy: default-src 'none'`. These are not mentioned anywhere in rest-api.md, and neither collection asserts their presence.
**Bruno:** No assertions on security response headers.
**Postman:** No assertions on security response headers.
**Docs (rest-api.md):** No mention of security response headers anywhere.
**Expected:** Docs should include a section (e.g. "Response Headers") listing the three headers and their values. Collections should optionally assert these headers are present.

---

## CORS — PATCH Method and Swagger Paths Not Documented

**Issue:** `PATCH` has been added to allowed CORS methods. CORS is also now applied to `/swagger-ui/**` and `/v3/api-docs/**` paths. Wildcard `*` is explicitly rejected for `allowed-origins`. None of this is documented in rest-api.md.
**Bruno:** N/A — CORS is a browser-side concern not exercised by Bruno/Postman directly.
**Postman:** N/A — same.
**Docs (rest-api.md):** CORS is not documented at all in rest-api.md.
**Expected:** Docs should include a CORS section listing allowed methods (GET, POST, PUT, PATCH, DELETE, OPTIONS), the wildcard restriction, and the paths covered.

---

## New Error Responses — Not Documented

**Issue:** The `GlobalExceptionHandler` now handles twelve additional exception types and returns structured `ErrorResponse` JSON bodies with specific HTTP status codes. Previously, some of these cases produced no body, a wrong status, or a leaked exception message. The docs have no error response section.
**Bruno:** No requests in either collection cover these error paths (e.g., malformed JSON body, wrong Content-Type, invalid path parameter type, missing required parameter, invalid sort field, method not supported).
**Postman:** Same — no negative-case coverage.
**Docs (rest-api.md):** No error response section exists. No mention of the `ErrorResponse` structure, the `IllegalArgumentException` change (was leaking `ex.getMessage()`, now returns `"Invalid request"`), or the new `JsonAuthenticationEntryPoint` providing a JSON body on 401s.
**Expected:** Docs should add an "Error Responses" section documenting the `ErrorResponse` body shape and the conditions that produce each HTTP error status, including the new handlers.

---

## GET /api/documents/{documentId}/comments — 404 vs 403 for Private Documents

**Issue:** Accessing comments on a private document as a non-owner now returns `404 Not Found` (intentional existence masking) instead of the previous `403 Forbidden`. The docs describe this endpoint as `Auth: Optional (anonymous allowed)` but say nothing about the visibility-based 404 masking behaviour.
**Bruno:** `get-comments-for-document.bru` sets `auth: none` and uses a hardcoded document ID — it cannot exercise the private-document masking path.
**Postman:** "Get Comments for Document" similarly has no auth header and cannot exercise the masking path.
**Docs (rest-api.md):** No mention of 404 masking. Listing only `200 OK` implies any failure is a generic 404/403 without clarifying the intentional behaviour.
**Expected:** Docs should add a note: "Returns `404 Not Found` for documents that are private and not owned by the authenticated user (masking existence)."

---

## JWT Token — Breaking Validator Change Not Documented

**Issue:** JWT tokens now carry `iss`, `aud`, and `jti` claims, and the validator enforces `iss` and `aud`. Any token issued before this change will be rejected. The changed error messages (`"JWT token has expired"` vs `"JWT token is invalid or malformed"`) are also undocumented. Neither collection has a test for rejected/expired tokens.
**Bruno:** No expired-token or pre-change-token test request.
**Postman:** No expired-token or pre-change-token test request.
**Docs (rest-api.md):** No mention of JWT claim requirements, expected error messages for expired/invalid tokens, or the breaking nature of this change.
**Expected:** Docs should note that tokens are validated against configured `iss` and `aud` values, and that tokens issued by a previous version of the service will be rejected with `401 Unauthorized`. The two distinct error messages should be listed.

---

## Bruno Environment vs Postman Variables — Token Variable Name Consistent, but Scoping Differs

**Issue:** Both collections use `baseUrl` and `authToken` as variable names, which is consistent. However, they scope them differently: Bruno uses an environment file (`environments/local.bru`) where `authToken` is marked as a secret variable; Postman stores both as plain collection-level variables with no secret marking. The Postman `authToken` variable has an empty default value and no description on `baseUrl` to clarify it is environment-specific.
**Bruno:** `local.bru` — `baseUrl: http://localhost:8080`; `authToken` is in `vars:secret []` (masked in UI, not committed to source).
**Postman:** Collection-level variables — `baseUrl: http://localhost:8080`; `authToken: ""` with a description `"JWT Bearer token — populate after calling POST /api/auth/login"`. No secret marking.
**Docs (rest-api.md):** N/A.
**Expected:** Postman should use an environment file (not collection variables) to store `authToken` so it is not committed alongside the collection. If collection variables must be used, the token value must remain empty in the committed file. The description on `authToken` also incorrectly says "after calling POST /api/auth/login" — it should also mention register.
