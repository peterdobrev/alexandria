I now have a thorough understanding of the codebase. Here is the implementation plan:

---

# Input Validation — Implementation Plan

## 1. Executive Summary

**Security risks** (authentication bypass / data corruption / content injection that a remote attacker can trigger without any elevated privilege):

- Finding 3 — whitespace-only password accepted at update time creates accounts with trivially-guessable credentials.
- Finding 11 — MIME-type check trusts the client-supplied `Content-Type` header, allowing executable or HTML/SVG files to be stored and later served inline under a trusted content-type (stored XSS / content-sniffing vector).
- Finding 13 — private documents can be bookmarked by any authenticated user who knows the UUID, both disclosing the document's existence and logging a BOOKMARK interaction against it.
- Finding 15 — `UsernameNotFoundException` bubbles as 500 instead of 401; leaks internal error detail and misrepresents the client's authentication state.
- Finding 16 — `InvalidTokenException` similarly produces 500 instead of 401.
- Finding 8 — unbounded `Pageable` page size allows a single authenticated request to force the server to load and serialise hundreds of thousands of rows (`GET /api/documents?size=100000`); realistic DoS vector.

**UX / contract correctness issues** (incorrect HTTP status codes, misleading success responses, or client-facing 500s that should be 400 or 409):

- Findings 4, 5 — reading list name longer than 255 chars silently reaches the DB and returns 500 instead of 400.
- Finding 6 — free-text `type` field: invalid values are persisted and proliferate without error; `CreateArticleRequest.type` additionally has no `@Size` cap, producing the same DB 500.
- Finding 9 — unbounded article body allows heap/DB exhaustion.
- Finding 14 — `addComment` has no `@PreAuthorize`; unauthenticated callers receive 500 rather than 401.
- Finding 17 — `IllegalArgumentException` used for sort validation is overly broad and could accidentally map unrelated library exceptions to 400.
- Finding 19 — concurrent duplicate category creation hits DB UNIQUE constraint and returns 500 instead of 409.

**UX issues with no security impact**:

- Finding 1 — empty PUT body for user update accepted as a silent no-op.
- Finding 2 — whitespace-only display name persisted.
- Finding 7 — unbounded `search` parameter; low-severity DoS risk via long LIKE strings.
- Finding 10 — unbounded `categoryIds` set triggers N+1 SELECT amplification.
- Finding 12 — file responses lack `X-Content-Type-Options: nosniff`; amplifies Finding 11 but is not independently exploitable if Finding 11 is fixed.

---

## 2. Prioritised Fix List

| # | DTO / Endpoint | Missing Constraint / Gap | Fix | Severity | Effort |
|---|---|---|---|---|---|
| 15 | `GlobalExceptionHandler` / all authenticated endpoints | `UsernameNotFoundException` unhandled → 500 | Add `@ExceptionHandler(UsernameNotFoundException.class)` returning 401 | High | XS |
| 16 | `GlobalExceptionHandler` / all JWT endpoints | `InvalidTokenException` unhandled → 500 | Add `@ExceptionHandler(InvalidTokenException.class)` returning 401 | High | XS |
| 3 | `UpdateUserRequest.password` | Whitespace-only password accepted | Add `@NullOrNotBlank` alongside existing `@Size(min=8, max=255)` | High | XS |
| 8 | `DocumentController`, `ReadingListController`, `CommentController` | No page-size cap | Global `PageableHandlerMethodArgumentResolverCustomizer` bean capping at 100 | High | S |
| 11 | `LocalFileStorageService.store()` | MIME type trusts client `Content-Type` | Add Apache Tika magic-byte detection; compare detected type against allowlist | High | M |
| 13 | `ReadingListService.addItem()` | No visibility check on bookmarked document | Apply same `assertVisible`-style guard used in `InteractionService` | Medium | S |
| 14 | `CommentController.addComment` | No `@PreAuthorize`; unauthenticated callers get 500 | Add `@PreAuthorize("isAuthenticated()")` | Medium | XS |
| 4 | `CreateReadingListRequest.name` | No `@Size(max=255)` | Add `@Size(max=255)` | Medium | XS |
| 5 | `UpdateReadingListRequest.name` | No `@Size(max=255)` | Add `@Size(max=255)` | Medium | XS |
| 6a | `CreateArticleRequest.type` | No `@Size(max=50)` | Add `@Size(max=50)` as immediate fix; then introduce `DocumentType` enum (see step 6b) | Medium | XS then S |
| 6b | `CreateDocumentRequest.type`, `CreateArticleRequest.type` | Free-text field, not validated against a known set | Introduce `DocumentType` enum; replace `String type` with `@NotNull DocumentType type` in both DTOs; update `Document.type` mapping | Medium | M |
| 9 | `CreateArticleRequest.body` | No `@Size` upper bound | Add `@Size(max=500_000)` | Medium | XS |
| 12 | `DocumentController.streamFile()` | No `X-Content-Type-Options` header | Add `X-Content-Type-Options: nosniff` response header; change disposition to `attachment` for non-PDF types | Medium | S |
| 2 | `UpdateUserRequest.displayName` | Whitespace accepted | Replace `@Size(max=255)` with `@NullOrNotBlank @Size(max=255)` | Medium | XS |
| 1 | `UpdateUserRequest` | Empty body silently succeeds | Add class-level `@AtLeastOneField` custom constraint | Medium | S |
| 19 | `GlobalExceptionHandler` / `POST /api/categories` | `DataIntegrityViolationException` → 500 | Add handler mapping to 409 | Low | XS |
| 7 | `DocumentController.list()` search param | No length cap | Add `@Validated` on controller + `@Size(max=200)` on `@RequestParam String search` | Low | XS |
| 10 | `CreateDocumentRequest.categoryIds`, `CreateArticleRequest.categoryIds` | No set-size cap | Add `@Size(max=20)` | Low | XS |
| 17 | `DocumentController.validateSort()` | Broad `IllegalArgumentException` | Introduce `InvalidSortFieldException extends IllegalArgumentException`; add dedicated `@ExceptionHandler` | Info | XS |

---

## 3. Implementation Steps

### Finding 15 — UsernameNotFoundException → 401

File: `exception/GlobalExceptionHandler.java`

Add before the `BadCredentialsException` handler:

```java
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExceptionHandler(UsernameNotFoundException.class)
public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex,
                                                             HttpServletRequest request) {
    log.warn("User not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
}
```

No new classes required. The message must not echo `ex.getMessage()` to avoid leaking which user account triggered the lookup failure; use the static string `"Authentication required"`.

---

### Finding 16 — InvalidTokenException → 401

File: `exception/GlobalExceptionHandler.java`

Add:

```java
import com.alexandria.exception.InvalidTokenException;

@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex,
                                                         HttpServletRequest request) {
    log.warn("Invalid token on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.UNAUTHORIZED, "Invalid or expired token", request);
}
```

---

### Finding 3 — Whitespace-only password in UpdateUserRequest

File: `dto/user/UpdateUserRequest.java`

Change:

```java
@Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
String password
```

To:

```java
@NullOrNotBlank(message = "Password must not be blank")
@Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
String password
```

Import: `com.alexandria.validation.NullOrNotBlank`. No new validator needed; `@NullOrNotBlank` already exists and rejects blank-but-non-null strings.

---

### Finding 8 — Unbounded page size (global cap)

See Section 4 for the strategy. Single implementation point:

New file: `config/PaginationConfig.java`

```java
package com.alexandria.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.PageableHandlerMethodArgumentResolverCustomizer;

@Configuration
public class PaginationConfig {

    private static final int MAX_PAGE_SIZE = 100;

    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableSizeCap() {
        return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
    }
}
```

No changes to any controller. `RecommendationController`'s manual size/page checks can remain as tighter additional guards for that endpoint; they do not conflict.

---

### Finding 11 — MIME type trusts client Content-Type

File: `storage/LocalFileStorageService.java`

Add dependency to `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.2</version>
</dependency>
```

`tika-core` (not `tika-parsers`) is sufficient for magic-byte detection and has no transitive dependency on parser libraries that would bloat the JAR.

Extract a private method in `LocalFileStorageService`:

```java
private String detectContentType(MultipartFile file) {
    try (var in = file.getInputStream()) {
        Tika tika = new Tika();
        return tika.detect(in, file.getOriginalFilename());
    } catch (IOException e) {
        throw new UncheckedIOException("Failed to read file for MIME detection", e);
    }
}
```

Replace the current content-type check:

```java
// Before
String contentType = file.getContentType();
if (contentType == null || !storageProperties.allowedContentTypes().contains(contentType)) {
    throw new InvalidDocumentContentException("Unsupported content type: " + contentType);
}

// After
String detectedContentType = detectContentType(file);
if (!storageProperties.allowedContentTypes().contains(detectedContentType)) {
    throw new InvalidDocumentContentException(
            "Unsupported content type: " + detectedContentType);
}
```

The `StoredFile` record should be updated to store `detectedContentType` instead of `file.getContentType()` so the served `Content-Type` header reflects the actual detected type.

Note: `new Tika()` is cheap to instantiate but can be extracted to a field if performance profiling shows otherwise.

---

### Finding 13 — Private document visibility in ReadingListService.addItem()

File: `service/ReadingListService.java`

After the document lookup at line 79, add a visibility check that mirrors `InteractionService.assertVisible()`:

```java
Document document = documentRepository.findById(request.documentId())
        .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));

if (document.getVisibility() == Visibility.PRIVATE
        && !document.getAuthor().getId().equals(list.getUser().getId())) {
    throw new DocumentNotFoundException(request.documentId());
}
```

The check should throw `DocumentNotFoundException` (not `ForbiddenException`) to avoid confirming the private document's existence to the caller — same pattern used in `DocumentService.get()` and `InteractionService.assertVisible()`.

Required import: `com.alexandria.entity.Visibility`.

---

### Finding 14 — addComment missing @PreAuthorize

File: `controller/CommentController.java`, line 44

Add `@PreAuthorize("isAuthenticated()")` to the `addComment` method:

```java
@PreAuthorize("isAuthenticated()")
@ResponseStatus(HttpStatus.CREATED)
@PostMapping
public CommentResponse addComment(...)
```

This makes the authentication requirement explicit at the controller layer, consistent with `deleteComment`, and ensures that the filter-level `anyRequest → authenticated` rule is also backed by a declarative annotation. With Finding 15 fixed, `securityUtils.getCurrentUser()` inside the method will return 401 rather than 500 for a deleted-user token, making the defence-in-depth complete.

---

### Findings 4 and 5 — Reading list name size cap

File: `dto/CreateReadingListRequest.java`

```java
public record CreateReadingListRequest(
        @NotBlank @Size(max = 255) String name
) {}
```

File: `dto/UpdateReadingListRequest.java`

```java
public record UpdateReadingListRequest(
        @NotBlank @Size(max = 255) String name
) {}
```

---

### Finding 6a — CreateArticleRequest.type missing @Size (immediate fix)

File: `dto/document/CreateArticleRequest.java`

```java
@NotBlank
@Size(max = 50)
String type,
```

---

### Finding 6b — DocumentType enum (full fix, supersedes 6a)

1. Create `entity/DocumentType.java`:

```java
package com.alexandria.entity;

public enum DocumentType {
    PDF,
    ARTICLE,
    BOOK,
    VIDEO,
    LINK
}
```

Add values to match whatever the existing live data contains. Query the DB or scan test fixtures before committing the enum members.

2. Update `dto/document/CreateDocumentRequest.java`: replace `String type` with:

```java
@NotNull
DocumentType type,
```

Remove the now-redundant `@NotBlank @Size(max=50)`.

3. Update `dto/document/CreateArticleRequest.java` identically.

4. Update `Document.type` entity field (for JPA):

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 50)
private DocumentType type;
```

5. Update `DocumentService.DocumentFilters`:

```java
public record DocumentFilters(DocumentType type, UUID categoryId, UUID authorId, String search) {}
```

6. Jackson will automatically return 400 for unknown enum values via `HttpMessageNotReadableException`, which `GlobalExceptionHandler` already handles and maps to 400 with `"Malformed request body"`.

7. The `DocumentController.list()` `type` query param must change from `String` to `DocumentType`:

```java
@RequestParam(required = false) DocumentType type,
```

`MethodArgumentTypeMismatchException` for an unknown enum value is already handled as 400.

---

### Finding 9 — Article body size cap

File: `dto/document/CreateArticleRequest.java`

```java
@NotBlank
@Size(max = 500_000)
String body,
```

500,000 characters is approximately 500 KB of plain text, which is a generous upper bound for an article. Adjust based on product requirements. For the network layer, also add to `application.properties`:

```properties
spring.servlet.multipart.max-request-size=${app.request.max-size:10MB}
```

(This property already controls file uploads; the JSON article endpoint is not multipart so this particular property does not apply to it. Tomcat's `server.tomcat.max-http-form-post-size` defaults to 2 MB for URL-encoded bodies but does not apply to `application/json`; a `@Size` annotation on the DTO field is the correct and sufficient guard for JSON bodies.)

---

### Finding 12 — X-Content-Type-Options on file responses

File: `controller/DocumentController.java`, method `streamFile()`

```java
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(sfr.contentType()))
        .contentLength(sfr.sizeBytes())
        .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + sanitize(sfr.originalFilename()) + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(sfr.resource());
```

Additionally, for non-PDF types (i.e., anything other than `application/pdf`), use `attachment` disposition to prevent inline rendering in the browser, which limits the attack surface if Finding 11 is not yet fully deployed:

```java
String disposition = "application/pdf".equals(sfr.contentType()) ? "inline" : "attachment";
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(sfr.contentType()))
        .contentLength(sfr.sizeBytes())
        .header(HttpHeaders.CONTENT_DISPOSITION,
                disposition + "; filename=\"" + sanitize(sfr.originalFilename()) + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(sfr.resource());
```

---

### Finding 2 — Whitespace-only displayName

File: `dto/user/UpdateUserRequest.java`

```java
@NullOrNotBlank(message = "Display name must not be blank")
@Size(max = 255, message = "Display name must be at most 255 characters")
String displayName,
```

---

### Finding 1 — Empty PUT body accepted as no-op

This requires a new class-level custom constraint `@AtLeastOneField`.

1. Create `validation/AtLeastOneField.java`:

```java
package com.alexandria.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = AtLeastOneFieldValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AtLeastOneField {
    String message() default "At least one field must be provided";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

2. Create `validation/AtLeastOneFieldValidator.java`:

```java
package com.alexandria.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.lang.reflect.RecordComponent;

public class AtLeastOneFieldValidator
        implements ConstraintValidator<AtLeastOneField, Record> {

    @Override
    public boolean isValid(Record value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                if (component.getAccessor().invoke(value) != null) {
                    return true;
                }
            } catch (ReflectiveOperationException ignored) {
                // treat as null
            }
        }
        return false;
    }
}
```

3. Annotate the record in `dto/user/UpdateUserRequest.java`:

```java
@AtLeastOneField(message = "At least one of displayName or password must be provided")
public record UpdateUserRequest(...)
```

---

### Finding 19 — DataIntegrityViolationException on concurrent category duplicate

File: `exception/GlobalExceptionHandler.java`

Add:

```java
import org.springframework.dao.DataIntegrityViolationException;

@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                   HttpServletRequest request) {
    log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.CONFLICT, "A resource with the same unique identifier already exists", request);
}
```

Caution: `DataIntegrityViolationException` is broad — it covers FK constraint failures and NOT NULL violations as well as UNIQUE failures. The static message above is intentionally generic; a more specific message would require inspecting the exception cause, which couples the handler to Hibernate internals. Accept the generic message.

---

### Finding 7 — search param length cap

File: `controller/DocumentController.java`

Add `@Validated` to the class declaration:

```java
@Validated
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {
```

Add `@Size` to the `search` parameter:

```java
@RequestParam(required = false) @Size(max = 200) String search,
```

Import `jakarta.validation.constraints.Size` and `org.springframework.validation.annotation.Validated`. `ConstraintViolationException` on the controller level is already handled by `GlobalExceptionHandler.handleConstraintViolation()` and maps to 400.

---

### Finding 10 — categoryIds set size cap

File: `dto/document/CreateDocumentRequest.java` and `dto/document/CreateArticleRequest.java`

```java
@Size(max = 20)
Set<UUID> categoryIds,
```

---

### Finding 17 — Broad IllegalArgumentException for sort validation

1. Create `exception/InvalidSortFieldException.java`:

```java
package com.alexandria.exception;

public class InvalidSortFieldException extends IllegalArgumentException {

    public InvalidSortFieldException(String field) {
        super("Sort field not allowed: " + field);
    }
}
```

2. Update `DocumentController.validateSort()`:

```java
throw new InvalidSortFieldException(order.getProperty());
```

3. Add a dedicated handler in `GlobalExceptionHandler`:

```java
import com.alexandria.exception.InvalidSortFieldException;

@ExceptionHandler(InvalidSortFieldException.class)
public ResponseEntity<ErrorResponse> handleInvalidSortField(InvalidSortFieldException ex,
                                                             HttpServletRequest request) {
    log.warn("Invalid sort field on {}: {}", request.getRequestURI(), ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
}
```

Place this handler before the `IllegalArgumentException` handler so it takes priority.

---

## 4. Pagination Cap Strategy

Use a single `PageableHandlerMethodArgumentResolverCustomizer` bean defined in a `@Configuration` class. This is the Spring Data Web integration point specifically designed for this purpose.

`resolver.setMaxPageSize(n)` causes Spring Data's `PageableHandlerMethodArgumentResolver` to silently clamp any `?size=` value greater than `n` down to `n`. The client receives a response with the clamped page size reflected in the returned `Page` metadata rather than a 400 error, which is the standard Spring Data behaviour.

Choose `MAX_PAGE_SIZE = 100` as a reasonable default that matches the `RecommendationController`'s existing guard (50) in spirit; `RecommendationController` can keep its explicit checks for the tighter 50-item cap on that endpoint.

Do not add per-controller manual checks to `DocumentController`, `ReadingListController`, or `CommentController`. The global bean covers all `Pageable`-injected endpoints uniformly, including any endpoints added in the future.

```java
// config/PaginationConfig.java
@Bean
public PageableHandlerMethodArgumentResolverCustomizer pageableSizeCap() {
    return resolver -> resolver.setMaxPageSize(100);
}
```

---

## 5. Recommended Implementation Order

Group the work into three passes, ordered by security impact and implementation dependency:

**Pass 1 — Exception handler fixes (no risk, no test-breakage, immediate security gain)**

1. Finding 15: Add `UsernameNotFoundException` handler (401).
2. Finding 16: Add `InvalidTokenException` handler (401).
3. Finding 19: Add `DataIntegrityViolationException` handler (409).
4. Finding 17: Introduce `InvalidSortFieldException`; add dedicated handler before `IllegalArgumentException` handler.

**Pass 2 — Input validation additions (DTO annotation changes; all covered by Bean Validation)**

5. Finding 3: `@NullOrNotBlank` on `UpdateUserRequest.password`.
6. Finding 2: `@NullOrNotBlank` on `UpdateUserRequest.displayName`.
7. Findings 4, 5: `@Size(max=255)` on both reading list name fields.
8. Finding 6a: `@Size(max=50)` on `CreateArticleRequest.type`.
9. Finding 9: `@Size(max=500_000)` on `CreateArticleRequest.body`.
10. Finding 10: `@Size(max=20)` on `categoryIds` in both document request DTOs.
11. Finding 7: Add `@Validated` to `DocumentController`; `@Size(max=200)` on `search` param.
12. Finding 14: Add `@PreAuthorize("isAuthenticated()")` to `CommentController.addComment`.

**Pass 3 — Structural changes (require new classes, migration, or third-party dependency)**

13. Finding 8: `PaginationConfig` bean with `PageableHandlerMethodArgumentResolverCustomizer`.
14. Finding 1: `@AtLeastOneField` custom constraint + validator.
15. Finding 13: Visibility guard in `ReadingListService.addItem()`.
16. Finding 12: `X-Content-Type-Options` header + conditional `attachment` disposition in `DocumentController.streamFile()`.
17. Finding 6b: `DocumentType` enum (coordinate with DB migration; run after 6a is deployed).
18. Finding 11: Apache Tika magic-byte detection in `LocalFileStorageService` (highest-effort item; coordinate with QA on allowlist values).

---

## 6. Acceptable As-Is

The following findings require no application-layer code change:

- **Finding 18** (UUID path variable mismatch): already handled correctly as 400 via `MethodArgumentTypeMismatchException`. No action needed.

- **`CreateDocumentRequest.type` size cap** (the `@NotBlank @Size(max=50)` already present): the DB column is `varchar(50)`; the existing annotation is sufficient until Finding 6b (enum) is implemented.

- **`Document.type` DB column**: the column currently has no DB CHECK constraint restricting it to known values. Once Finding 6b is shipped (enum binding), the Java layer enforces the set; a subsequent Liquibase migration adding a CHECK constraint would be belt-and-suspenders but is not required for correctness.

- **`CreateCommentRequest.body`**: already has `@NotBlank @Size(min=1, max=2000)` — no gap.

- **`documents.description` and `categories.name`**: already have matching `@Size` annotations against their DB column lengths.

- **Reading list item uniqueness**: already guarded at the service layer (`readingListItemRepository.findByReadingListIdAndDocumentId` check before save). The DB also has a UNIQUE constraint as a safety net; the service-level check will catch the common case before a constraint violation occurs.
