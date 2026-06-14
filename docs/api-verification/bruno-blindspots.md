# Bruno Collection Blindspots

## 1. AuthResponse — Post-response script captures only `token`, misses new fields

**File:** `docs/bruno/alexandria-api/auth/login-user.bru`
**Issue:** The post-response script reads `res.body.token` and stores it as `authToken`. The AuthResponse shape has not changed for that field, so the token capture itself still works. However, the script does not capture `res.body.userId` into an environment variable (e.g. `userId`), even though that field is now always present and is needed to exercise user-scoped endpoints such as `GET /api/users/{id}` and the `Location` header returned on register. All other new fields (`tokenType`, `expiresIn`) go completely unverified.
**Expected:** Script should also set `bru.setEnvVar("userId", res.body.userId)`, and optionally assert `res.body.tokenType === "Bearer"` and `res.body.expiresIn > 0`.
**Current:**
```js
if (res.status === 200) {
  bru.setEnvVar("authToken", res.body.token);
}
```

---

## 2. AuthResponse — Post-response script on register misses new fields

**File:** `docs/bruno/alexandria-api/auth/register-user.bru`
**Issue:** Same problem as login. The script captures only `res.body.token`. The new `userId`, `tokenType`, and `expiresIn` fields are ignored. Additionally, the script does not assert that a `Location` response header is present (pointing to `/api/users/{userId}`), which is a newly added behaviour on `POST /api/auth/register`.
**Expected:** Script should set `bru.setEnvVar("userId", res.body.userId)`, assert `res.body.tokenType === "Bearer"`, assert `res.body.expiresIn > 0`, and assert the `Location` header matches `http://localhost:8080/api/users/<uuid>`.
**Current:**
```js
if (res.status === 201) {
  bru.setEnvVar("authToken", res.body.token);
}
```

---

## 3. POST /api/documents/{documentId}/comments — No Location header assertion

**File:** `docs/bruno/alexandria-api/comments/add-comment-to-document.bru`
**Issue:** The endpoint now returns `201 Created` with a `Location` header (`<current-path>/{commentId}`). The Bruno file has no post-response script and no assertion that checks the `Location` header or captures the `commentId` from it. The response status was implicitly expected to be `200` (no explicit check exists), but it is now `201`.
**Expected:** A post-response script that asserts `res.status === 201` and captures the `commentId` from the `Location` header into an env var (e.g. `bru.setEnvVar("commentId", ...)`).
**Current:** No `script:post-response` block; no status or header assertion.

---

## 4. POST /api/reading-lists/{id}/items — No Location header assertion

**File:** `docs/bruno/alexandria-api/reading-lists/add-item-to-reading-list.bru`
**Issue:** The endpoint now returns `201 Created` with a `Location` header (`<current-path>/{documentId}`). The Bruno file has no post-response script and therefore no assertion on the `Location` header or the `201` status code.
**Expected:** A post-response script that asserts `res.status === 201` and optionally verifies the `Location` header is a well-formed absolute URL ending in the document ID.
**Current:** No `script:post-response` block.

---

## 5. POST /api/auth/register — Location header not asserted

**File:** `docs/bruno/alexandria-api/auth/register-user.bru`
**Issue:** `POST /api/auth/register` now sets a `Location` response header to `<context-path>/api/users/{userId}`. This is a new observable behaviour that is not tested anywhere in the collection. The file has no assertion on any response header.
**Expected:** Post-response script asserting `res.headers['location']` matches the pattern `http://localhost:8080/api/users/<uuid>`.
**Current:** No header assertions.

---

## 6. GET /api/documents/{documentId}/comments — auth mode set to `none`, but private document access now returns 404 for non-owners

**File:** `docs/bruno/alexandria-api/comments/get-comments-for-document.bru`
**Issue:** The request uses `auth: none`, which is correct for public documents. However, the behaviour change means that accessing comments on a private document as a non-authenticated or non-owner caller now returns `404 Not Found` instead of `403 Forbidden`. The collection has no second request (with auth) that demonstrates the authenticated path for accessing comments on a private document owned by the caller. This leaves the access-control behaviour change completely untested in the collection.
**Expected:** Either a note in the file or a paired request that tests the authenticated path; the existing file should at minimum document that `404` (not `403`) is the expected response when the document is private and the caller is not the owner.
**Current:** Single unauthenticated request with no status assertions, no note about the 403 → 404 privacy masking change.

---

## 7. POST /api/documents (multipart) — No unauthenticated test case; auth enforcement silently assumed

**File:** `docs/bruno/alexandria-api/documents/upload-file-document.bru`
**Issue:** The endpoint previously had no explicit `@PreAuthorize` guard, but now enforces `isAuthenticated()` at the method level. The Bruno file correctly includes `auth: bearer`, but there is no negative test (or note) verifying that an unauthenticated call gets `401 Unauthorized` with the new structured `ErrorResponse` JSON body (not a bare `401` with no body, which was the old behaviour).
**Expected:** A note or paired `.bru` file demonstrating that calling this endpoint without a token returns `401` with a JSON body `{"status": 401, "error": "Unauthorized", ...}`.
**Current:** Only the authenticated happy-path request exists; the `401` error shape change is not covered.

---

## 8. POST /api/documents/article — No unauthenticated test case; same auth enforcement gap

**File:** `docs/bruno/alexandria-api/documents/create-article-document.bru`
**Issue:** Same as #7. The endpoint now has an explicit `@PreAuthorize("isAuthenticated()")`. The Bruno file uses bearer auth (correct), but there is no negative test verifying the `401` structured error response shape for unauthenticated callers.
**Expected:** Same as #7.
**Current:** Only the authenticated happy-path request exists.

---

## 9. CreateArticleRequest — `type` field uses non-standard free-text value

**File:** `docs/bruno/alexandria-api/documents/create-article-document.bru`
**Issue:** The sample body sets `"type": "ARTICLE"`. The `CreateArticleRequest.type` field now has `@Size(max = 50)` and is `@NotBlank`, but its actual accepted values are determined by the service layer. More critically, the sample value `"ARTICLE"` is suspicious — `POST /api/documents/article` is already the article-creation endpoint, so sending a `type` field of `"ARTICLE"` may conflict with how the service resolves the document type. The collection offers no negative test for `type` values exceeding 50 characters (the new `@Size(max=50)` constraint).
**Expected:** Clarify whether `type` is required in this request at all (the endpoint is already `/article`). Add a negative test with a `type` value longer than 50 characters expecting `400`.
**Current:** `"type": "ARTICLE"` with no constraint boundary tests.

---

## 10. CreateArticleRequest — `body` field has no length boundary test

**File:** `docs/bruno/alexandria-api/documents/create-article-document.bru`
**Issue:** The `body` field now has `@Size(max = 500_000)`. The sample value in the collection is a short string. There is no test verifying that a payload exceeding 500,000 characters returns `400 Bad Request`.
**Expected:** A paired negative test or at least a comment noting the 500,000-character limit.
**Current:** Only a short sample value; no boundary test.

---

## 11. CreateReadingListRequest / UpdateReadingListRequest — `name` has no length boundary test

**Files:** `docs/bruno/alexandria-api/reading-lists/create-reading-list.bru`, `docs/bruno/alexandria-api/reading-lists/update-reading-list.bru`
**Issue:** Both DTOs now have `@Size(max = 255)` on `name`. Neither Bruno file includes a negative test sending a name longer than 255 characters to verify the `400` response.
**Expected:** Negative test cases with names exceeding 255 characters.
**Current:** Only happy-path samples; no boundary tests.

---

## 12. UpdateUserRequest — `displayName` and `password` accept `null` but not blank strings

**File:** `docs/bruno/alexandria-api/users/update-user.bru`
**Issue:** Both fields now carry `@NullOrNotBlank`. This means `""` or `"   "` is invalid (returns `400`), but omitting the field entirely (`null`) is valid. The Bruno file always sends both fields as non-null, non-blank strings. There is no test for sending a blank string (e.g. `"displayName": "  "`) to verify the `400` response, nor a test demonstrating that `null` (or field omission) is accepted for a partial update.
**Expected:** A negative test with `"displayName": ""` or `"displayName": "   "` expecting `400`, and a positive test sending only one field (the other absent/null) expecting `200`.
**Current:** Single sample always providing both fields as non-blank strings.

---

## 13. `categoryIds` — `@Size(max = 20)` constraint not tested anywhere

**Files:** `docs/bruno/alexandria-api/documents/create-article-document.bru`, `docs/bruno/alexandria-api/documents/upload-file-document.bru`
**Issue:** Both `CreateArticleRequest.categoryIds` and `CreateDocumentRequest.categoryIds` now have `@Size(max = 20)`. Neither file includes a negative test sending more than 20 category IDs to verify the `400` response.
**Expected:** Negative test with 21+ category UUIDs expecting `400`.
**Current:** Only one category ID in each sample; no boundary tests.

---

## 14. Unauthenticated error responses now return structured JSON body — not tested

**Files:** All files that currently use `auth: bearer` (e.g. categories, documents, reading-lists, comments, recommendations, users)
**Issue:** Previously, unauthenticated requests (no or invalid token) returned a bare HTTP `401` with no body. The `JsonAuthenticationEntryPoint` now returns a structured `ErrorResponse` JSON body. No file in the collection includes a test that sends a request without credentials (or with a tampered token) to verify this new JSON shape.
**Expected:** At least one `.bru` file per protected resource group that sends no `Authorization` header and asserts `res.status === 401` and `res.body.message` is present.
**Current:** No negative auth tests anywhere in the collection.

---

## 15. `IllegalArgumentException` error message change — not covered

**File:** Any endpoint that previously documented the error message from `IllegalArgumentException`
**Issue:** The `GlobalExceptionHandler` previously leaked `ex.getMessage()` to the client on `IllegalArgumentException`. It now always returns the fixed string `"Invalid request"`. The Bruno collection has no test that deliberately triggers an `IllegalArgumentException` (e.g. by sending a disallowed sort field like `sort=invalidField`) and asserts the exact message, so this information-hiding fix is not validated in the collection. The `list-documents.bru` file uses a `sort` param commented out — a test with an invalid sort value would exercise this path.
**Expected:** A negative test on `GET /api/documents?sort=invalidField` asserting `res.status === 400` and `res.body.message === "Invalid sort property: invalidField"` (now handled by `PropertyReferenceException` handler, not the `IllegalArgumentException` handler).
**Current:** Only commented-out sort params; no invalid-sort negative test.

---

## 16. `size` pagination parameter — no test for the new cap of 100

**Files:** `docs/bruno/alexandria-api/documents/list-documents.bru`, `docs/bruno/alexandria-api/reading-lists/get-reading-lists.bru`, `docs/bruno/alexandria-api/recommendations/get-document-recommendations.bru`, `docs/bruno/alexandria-api/comments/get-comments-for-document.bru`
**Issue:** All pageable endpoints now have a maximum `size` of 100 (silently capped by `PaginationConfig`). The collection uses `size: 20` in samples (within the cap), but there is no test sending `size=200` to observe that the response contains at most 100 items and does not return a `400`.
**Expected:** A test with `size=200` asserting the response page size is capped at 100 and status is `200`.
**Current:** Only `size: 20` (commented out) in samples.

---

## 17. Environment variable `userId` — not captured, causing hardcoded IDs throughout the collection

**File:** `docs/bruno/alexandria-api/environments/local.bru`
**Issue:** The `local.bru` environment defines `baseUrl` and `authToken` but no `userId`. Since `AuthResponse` now always returns `userId`, the register/login scripts could capture it. Instead, every file that needs a user ID uses a hardcoded placeholder UUID (e.g. `a1b2c3d4-e5f6-7890-abcd-ef1234567890`). This means requests to `GET /api/users/{id}`, `PUT /api/users/{id}` will always hit the wrong user unless the tester manually replaces the ID.
**Expected:** `local.bru` should declare a `userId` variable; the auth post-response scripts should populate it from `res.body.userId`.
**Current:** No `userId` variable in the environment; hardcoded UUIDs used everywhere.

---

## 18. Security response headers — not asserted anywhere

**Files:** All `.bru` files
**Issue:** All responses now include `Strict-Transport-Security`, `Referrer-Policy`, and `Content-Security-Policy` headers. No Bruno file asserts these headers are present. This means a regression removing them (e.g. a misconfigured `SecurityConfig`) would go undetected by the collection.
**Expected:** At least one representative request (e.g. `list-documents.bru` or `login-user.bru`) with a post-response script asserting the three security headers are present with expected values.
**Current:** No security header assertions in any file.
