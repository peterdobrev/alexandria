# Adversarial Verification of 03-authorization-access-control.md

**Date:** 2026-06-14  
**Reviewer:** Adversarial Claude Code (subagent)  
**Method:** Full call-chain tracing — every claim checked against the literal source. IDOR findings traced from controller through service. Role findings traced through SecurityConfig AND @PreAuthorize AND service layer.

---

## Finding 1 — `GET /api/users/{id}` exposes profile to unauthenticated callers

**Verdict: CONFIRMED**

**Evidence:**
- `SecurityConfig.java` line 99: `.requestMatchers(HttpMethod.GET, "/api/users/{id}").permitAll()` — verified present.
- `UserController.java` lines 33–35: `@GetMapping("/{id}")` has no `@PreAuthorize` and returns `userService.get(id)` — verified.
- `UserService.get()` has no authentication guard; it only checks whether the user exists.

**Accuracy check:** The report correctly identifies the line numbers and the scope of information disclosed (`UserSummary` containing only `id` and `displayName`). The LOW severity rating is appropriate. The "rate-limiting" sub-claim cannot be confirmed or denied from source code alone (rate limiting could exist at the infrastructure layer), but no application-level rate limiter is visible here.

**No inaccuracies found.**

---

## Finding 2 — `GET /api/reading-lists` missing `@PreAuthorize`

**Verdict: CONFIRMED, but with a significant error in the severity argument — the actual failure mode is BETTER than reported**

**Evidence:**
- `ReadingListController.java` lines 38–40: no `@PreAuthorize` present — confirmed.
- `SecurityConfig.java` line 103: `.anyRequest().authenticated()` — the catch-all is present.
- The report claims that `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException` (a 500, not a 401) when called unauthenticated.

**Error in the report:** `GlobalExceptionHandler.java` lines 205–210 explicitly handles `UsernameNotFoundException` and maps it to **HTTP 401** with the message "Authentication required". The report states this produces "an unintended 500 instead of a 401" — this is **factually wrong**. The error response is a clean 401, not a 500.

The defence-in-depth concern (no `@PreAuthorize` at the method level) is a real but low-urgency observation. The underlying authentication gate still works correctly in two layers: the `anyRequest().authenticated()` catch-all in `SecurityConfig`, and the `UsernameNotFoundException` → 401 path in `GlobalExceptionHandler`. The MEDIUM severity rating is overstated given this correction; this is a defence-in-depth / code-clarity issue, not a functional security gap.

---

## Finding 3 — `POST /api/reading-lists` missing `@PreAuthorize`

**Verdict: CONFIRMED, same error as Finding 2**

**Evidence:**
- `ReadingListController.java` lines 43–45: no `@PreAuthorize` present — confirmed.
- Same `anyRequest().authenticated()` catch-all applies.
- Same incorrect claim: `UsernameNotFoundException` does NOT produce a 500. `GlobalExceptionHandler` lines 205–210 map it to **HTTP 401**. The report says "producing an unintended 500 instead of a 401" — this is **wrong**.

The concern about missing `@PreAuthorize` is valid as a defence-in-depth observation; the 500 claim is inaccurate.

---

## Finding 4 — `GET /api/recommendations` missing `@PreAuthorize`

**Verdict: CONFIRMED as a code-clarity observation; severity overstated**

**Evidence:**
- `RecommendationController.java` line 38: `@GetMapping("/recommendations")` has no `@PreAuthorize` — confirmed.
- `SecurityConfig.java` line 101: `.requestMatchers(HttpMethod.GET, "/api/recommendations").authenticated()` — an explicit `authenticated()` rule is present for this exact path, not just a catch-all.

**Additional accuracy issue:** The report accurately notes the `SecurityConfig` line 101 explicit rule but still rates this MEDIUM. With an explicit path-level `authenticated()` rule in `SecurityConfig` (not just the generic catch-all), the defence-in-depth gap is narrower than for Findings 2 and 3. The functional security is intact. The defence-in-depth concern is real but this warrants LOW, not MEDIUM.

---

## Finding 5 — `POST /api/documents/{id}/interactions` missing `@PreAuthorize`

**Verdict: CONFIRMED as a code-clarity observation; severity overstated**

**Evidence:**
- `RecommendationController.java` lines 52–61: no `@PreAuthorize` — confirmed.
- `SecurityConfig.java` line 100: `.requestMatchers(HttpMethod.POST, "/api/documents/*/interactions").authenticated()` — explicit `authenticated()` rule present for this exact method+path combination.
- `securityUtils.getCurrentUser()` would throw `UsernameNotFoundException` → HTTP 401 (via `GlobalExceptionHandler`), not 500 as implied.

The report is correct that there is no `@PreAuthorize`, but the functional security is intact at two layers. MEDIUM severity is overstated.

---

## Finding 6 — Document owner cannot delete comments on their own document

**Verdict: CONFIRMED**

**Evidence:**
- `CommentController.java` line 57: `@PreAuthorize("@ownership.isCommentOwnerOrAdmin(#commentId, principal)")` — confirmed.
- `OwnershipService.java` lines 56–69: the method checks admin status then checks `comment.getAuthor().getEmail().equals(principal.getUsername())`. There is no check for the document's author.
- The document author is not granted any deletion rights over comments on their document.

**Call chain trace:** `deleteComment` in `CommentService` (lines 52–58) performs no ownership check at all — it simply verifies the comment belongs to the given document and deletes it. The sole authorization gate is the `@PreAuthorize` on the controller, which does not include document ownership.

The finding and the suggested fix (adding a document author check inside `isCommentOwnerOrAdmin`) are accurate. Note: this is duplicated as Finding 14, which the report itself acknowledges.

---

## Finding 7 — `PUT /api/documents/{id}` admin cannot update documents

**Verdict: CONFIRMED**

**Evidence:**
- `DocumentController.java` line 100: `@PreAuthorize("@ownership.isDocumentOwner(#id, principal)")` — no `or hasRole('ADMIN')` — confirmed.
- `DocumentController.java` line 107: `@PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")` on `DELETE` — the asymmetry exists exactly as described.
- `OwnershipService.isDocumentOwner()` (lines 28–35): checks only document author email against principal username. No admin bypass.
- `DocumentService.update()` (lines 94–113): no role check inside the service.

The finding is accurate. LOW severity is appropriate — this is an operational gap, not an attacker-exploitable vulnerability.

---

## Finding 8 — Private document file stream visibility check duplicated

**Verdict: CONFIRMED**

**Evidence:**
- `DocumentService.java` lines 139–142 (`get`): visibility check — confirmed.
- `DocumentService.java` lines 175–178 (`streamFile`): identical check — confirmed.
- The two blocks are character-for-character identical, using `Visibility.PRIVATE` and `document.getAuthor().getId().equals(currentUserId)`.

The report correctly identifies this as duplicated logic that could diverge. The description is accurate. LOW severity is appropriate.

**Minor over-reach in report:** The report calls this "a security concern" when visibility diverges in the future, which is speculative. Currently both copies are identical — the latent risk is real but the present state is not incorrect. The code quality observation is valid.

---

## Finding 9 — `CommentService.assertVisible` uses email string comparison instead of ID

**Verdict: CONFIRMED**

**Evidence:**
- `CommentService.java` lines 61–69: `assertVisible` accepts `String currentUserEmail` and compares with `document.getAuthor().getEmail()` — confirmed.
- `DocumentService.java` lines 139–141 and 175–177: both use `document.getAuthor().getId().equals(currentUserId)` (UUID comparison) — confirmed.
- The inconsistency exists exactly as described.

The email is sourced from `Authentication.getName()` in `CommentController` (line 40, via `resolveCurrentUserEmail`) — the report correctly traces this to `Authentication.getName()`. The fragility argument (email changes) is valid, though no current email-change feature exists. LOW severity is appropriate.

---

## Finding 10 — `addComment` — `@PreAuthorize` vs SecurityConfig `permitAll` conflict

**Verdict: CONFIRMED AS NON-FINDING — correctly self-assessed by the report**

**Evidence:**
- `SecurityConfig.java` line 96: `.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()` — scoped to `GET` only — confirmed.
- `CommentController.java` line 44: `@PreAuthorize("isAuthenticated()")` on `@PostMapping` — confirmed.

The report correctly identifies there is no real conflict. The `permitAll` is GET-only; `POST` is protected by `@PreAuthorize`. The finding is accurately classified as no-issue for current code.

---

## Finding 11 — SecurityConfig `permitAll` for `/api/documents/*/comments` Ant wildcard

**Verdict: CONFIRMED AS LOW-RISK OBSERVATION**

**Evidence:**
- `SecurityConfig.java` line 96: `.requestMatchers(HttpMethod.GET, "/api/documents/*/comments").permitAll()` — confirmed.
- The `*` wildcard in Spring's Ant matcher matches exactly one path segment (no slashes), which is the correct and intended behavior for matching `{documentId}`.
- The pattern does NOT match `/api/documents/{id}/comments/{commentId}` because `*` does not match across `/`.

The report's analysis is technically accurate. The concern is future-proofing documentation, not a present vulnerability.

---

## Finding 12 — Recommendation data correctly scoped — no cross-user IDOR

**Verdict: CONFIRMED AS NON-FINDING**

**Evidence:**
- `RecommendationController.java` lines 48–49: `securityUtils.getCurrentUser()` then `currentUser.getId()` is passed to the service — confirmed.
- No user-supplied `userId` parameter in the path or query.

Accurate non-finding.

---

## Finding 13 — No role escalation via `PUT /api/users/{id}`

**Verdict: CONFIRMED AS NON-FINDING**

**Evidence:**
- `UserService.java` lines 31–42: only `displayName` and `passwordHash` are set — confirmed.
- The `UpdateUserRequest` DTO fields traced to confirm: only `displayName` and `password` — confirmed by the report's code quote and consistent with `UserService.update()`.

Accurate non-finding.

---

## Finding 14 — Document owner cannot delete comments (OwnershipService gap)

**Verdict: CONFIRMED — duplicate of Finding 6, same evidence**

The report explicitly acknowledges it is a restatement of Finding 6. Both the finding and the acknowledgment of the duplicate are accurate.

---

## Finding 15 — Reading list service layer has no ownership defence-in-depth

**Verdict: PARTIALLY-CORRECT — the observation is real, but the scope is overstated for some methods**

**Evidence:**
- `ReadingListService.getReadingLists()` (line 42): takes `User currentUser` directly and calls `findByUserId(currentUser.getId(), pageable)` — this method is inherently scoped to the current user. No additional ownership check is needed because the user object is passed in as a trusted argument, not a user-supplied ID. There is no IDOR possible here.
- `ReadingListService.createReadingList()` (line 47): takes `User currentUser` and assigns it to the list. Again, inherently scoped — no separate ownership check needed.
- `ReadingListService.getReadingList(UUID id)` (lines 56–60): fetches by ID with no ownership assertion — confirmed gap.
- `ReadingListService.updateReadingList(UUID id, ...)` (lines 63–68): fetches by ID with no ownership assertion — confirmed gap.
- `ReadingListService.deleteReadingList(UUID id)` (lines 70–73): fetches by ID with no ownership assertion — confirmed gap.
- `ReadingListService.addItem(UUID listId, ...)` (lines 76–94): fetches by ID with no ownership assertion — confirmed gap. Note: this method DOES check document visibility (line 81–83) but not reading list ownership.
- `ReadingListService.removeItem(UUID listId, UUID documentId)` (lines 97–103): fetches list by ID with no ownership assertion — confirmed gap.

The report claims ALL five operations (`getReadingList`, `updateReadingList`, `deleteReadingList`, `addItem`, `removeItem`) lack service-layer ownership checks. This is accurate for those five. The report also implies `getReadingLists` and `createReadingList` are in this category (by saying "All write and read operations") but those two are protected by design (user-scoped via the passed `currentUser` argument), so including them in the concern is inaccurate.

The core finding — that the five ID-parameterized operations have no service-layer ownership check — is confirmed. The defence-in-depth recommendation is valid. LOW severity is appropriate.

---

## Finding 16 — Admin can delete but not edit documents — asymmetric privilege

**Verdict: CONFIRMED — acknowledged duplicate of Finding 7**

Same evidence as Finding 7. The report correctly notes this is a restatement. No new inaccuracies.

---

## Finding 17 — `authorId` filter leaks private document count

**Verdict: CONFIRMED AS NON-FINDING — the report correctly self-refutes**

**Evidence:**
- `DocumentService.list()` lines 148–151: when `currentUserId` is non-null, uses `DocumentSpecifications.isVisibleToUser(currentUserId)`.
- `DocumentSpecifications.isVisibleToUser()` (lines 40–45): generates `WHERE (visibility = PUBLIC OR author_id = currentUserId)`.
- When combined with `hasAuthor(victimId)` (for an attacker where `currentUserId != victimId`), the SQL becomes: `WHERE (visibility = PUBLIC OR author_id = attackerId) AND author_id = victimId`. Since `attackerId != victimId`, the `author_id = attackerId` clause never matches for victim documents. Result: only `visibility = PUBLIC AND author_id = victimId` rows are returned.

The report correctly traces this logic and self-refutes, concluding: "No vulnerability." The analysis is accurate.

---

## Errors and Inaccuracies Found

### Critical Error — Findings 2, 3 (and by implication 4, 5)

The report states that `SecurityUtils.getCurrentUser()` throws `UsernameNotFoundException` and that "depending on the exception handler, this may return a 500 Internal Server Error, leaking stack information rather than a clean 401."

This is **factually wrong**. `GlobalExceptionHandler.java` lines 205–210 explicitly handles `UsernameNotFoundException`:

```java
@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                            HttpServletRequest request) {
    log.warn("User account not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

This returns HTTP **401** with the message "Authentication required" — a clean, intentional response. No stack trace is leaked. The "unintended 500" claim in Findings 2 and 3 is incorrect.

This means the practical risk of Findings 2–5 is lower than stated. The endpoints are protected by:
1. `anyRequest().authenticated()` or a path-specific `authenticated()` rule in `SecurityConfig`
2. `UsernameNotFoundException` → HTTP 401 via `GlobalExceptionHandler` as a second gate

The defence-in-depth observation (no `@PreAuthorize` at the method level) is still valid, but the severity should be LOW for all four, not MEDIUM.

### Scope Over-statement — Finding 15

The report implies `getReadingLists` and `createReadingList` lack ownership protection. Both are correctly scoped via the `User currentUser` parameter — `getReadingLists` calls `findByUserId(currentUser.getId(), pageable)` and `createReadingList` binds the list to `currentUser`. These two are not affected by the defence-in-depth gap described. The five ID-parameterized service methods are the real scope.

### Severity Over-statement — Findings 2–5

Given the corrected understanding of `GlobalExceptionHandler`, all four findings should be rated LOW (code clarity / defence-in-depth), not MEDIUM.

---

## Verified Non-Findings from the Report

All items in the report's "Non-Findings" table were checked:

| Item | Verification |
|---|---|
| IDOR on document fetch | CLEAN — `DocumentService.get()` and `streamFile()` both enforce visibility via UUID comparison |
| IDOR on document list | CLEAN — `DocumentSpecifications.isVisibleToUser()` correctly filters |
| Admin-only category ops | CLEAN — `CategoryController` has `@PreAuthorize("hasRole('ADMIN')")` on `POST`, `PUT`, `DELETE` (lines 38, 49, 57) |
| User A modifying User B's reading list | CLEAN — all five ID-parameterized endpoints have `@ownership.isReadingListOwner` |
| User A modifying User B's document | CLEAN — `@ownership.isDocumentOwner` on `PUT` |
| User A deleting User B's document | CLEAN — `@ownership.isDocumentOwner or hasRole('ADMIN')` on `DELETE` |
| User A updating User B's profile | CLEAN — `@ownership.isSelf(#id, principal)` on `PUT /api/users/{id}` |
| Role escalation via profile update | CLEAN — `UpdateUserRequest` has no role field; `UserService.update()` only sets `displayName` and `passwordHash` |
| Comments on private docs visible to non-owners | CLEAN — `CommentService.assertVisible()` enforces visibility |
| Recommendations exposing other users' data | CLEAN — scoped to `currentUser.getId()` |
| `GET /api/reading-lists` returning another user's lists | CLEAN — `findByUserId(currentUser.getId(), pageable)` |

---

## Summary Counts

| Category | Count |
|---|---|
| CONFIRMED (accurate finding) | 10 (Findings 1, 6, 7, 8, 9, 11, 14, 15-partial, 16, and the non-finding self-corrections for 10 and 17) |
| CONFIRMED with severity overstated | 4 (Findings 2, 3, 4, 5 — defence-in-depth concern is real, but MEDIUM is wrong given the 401 handler) |
| PARTIALLY-CORRECT | 1 (Finding 15 — the service-level gap exists for 5 methods but `getReadingLists` and `createReadingList` are not affected) |
| REFUTED | 0 |
| Non-findings correctly identified | 6 (Findings 10, 12, 13, 17; plus the non-finding table items) |
| Factual errors in the report | 2 (the "500 instead of 401" claim in Findings 2–3; the over-inclusion of `getReadingLists`/`createReadingList` in Finding 15) |

### Most Important Correction

The claim in Findings 2 and 3 that `UsernameNotFoundException` produces "an unintended 500 Internal Server Error, leaking stack information" is incorrect. `GlobalExceptionHandler` explicitly converts this to HTTP 401 with the generic message "Authentication required". The unauthenticated-call failure mode is clean and intentional. This materially reduces the urgency of Findings 2–5 from MEDIUM to LOW.

### Confirmed Real Vulnerabilities

The following findings from the report are accurate and warrant action, in priority order:

1. **Finding 6/14** — Document owner cannot delete comments on their own document. The `@PreAuthorize` on `deleteComment` checks only comment ownership or admin, never document ownership. Confirmed real gap.
2. **Finding 7/16** — Admin cannot edit documents they can delete. Asymmetry in `@PreAuthorize` between `PUT` and `DELETE` on documents is confirmed.
3. **Finding 9** — `CommentService.assertVisible` uses email comparison instead of UUID. Inconsistent with the rest of the codebase.
4. **Findings 2–5** — Missing `@PreAuthorize` at the method level (defence-in-depth gap). Real but LOW severity.
5. **Finding 8** — Duplicated visibility logic between `get()` and `streamFile()`. Latent risk.
6. **Finding 15** — Five `ReadingListService` methods lack service-level ownership checks (defence-in-depth gap).
