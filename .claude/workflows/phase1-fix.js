export const meta = {
  name: 'phase1-fix',
  description: 'Fix Phase 1 spec deviations on Feature/crud-endpoints. Worktree-isolated parallel slices, sequential apply, build+test verification, per-slice commits, no push.',
  phases: [
    { title: 'Author', detail: 'Each slice authors its files in an isolated worktree and returns a patch' },
    { title: 'Apply', detail: 'Driver applies patches sequentially with per-slice commits' },
    { title: 'Verify', detail: 'mvn compile + mvn test on the final tree' },
  ],
}

const REPO = '/Users/I765724/Documents/alexandria'
const SPEC_REF = 'docs/architecture-and-phase-1:docs/superpowers/specs/2026-06-08-phase-1-documents-and-categories-design.md'
const ARCH_REF = 'docs/architecture-and-phase-1:docs/superpowers/specs/2026-06-08-alexandria-architecture-design.md'

const PATCH_SCHEMA = {
  type: 'object',
  required: ['summary', 'files'],
  additionalProperties: false,
  properties: {
    summary: { type: 'string' },
    notes: { type: 'string' },
    files: {
      type: 'array',
      items: {
        type: 'object',
        required: ['path', 'action', 'content'],
        additionalProperties: false,
        properties: {
          path: { type: 'string' },
          action: { enum: ['create', 'modify', 'delete'] },
          content: { type: 'string' },
        },
      },
    },
  },
}

const BRIEF_LINES = [
  'You are running in an isolated git worktree of the alexandria repo. The working tree starts at the tip of feature/crud-endpoints, which has main already merged into it.',
  '',
  'You MUST:',
  '1. Read the Phase 1 sub-spec via: git show ' + SPEC_REF,
  '2. Read the architecture spec via: git show ' + ARCH_REF,
  '3. Read CONTRIBUTING via: git show docs/architecture-and-phase-1:CONTRIBUTING.md',
  '4. Read whatever existing files in the working tree you need (use Read tool on repo paths).',
  '5. Author your slice files in the worktree using Write/Edit. You may run any read-only command. Do NOT run mvn or git commit.',
  '6. Return your patch as the StructuredOutput files array with FULL file content. Paths must be RELATIVE TO REPO ROOT (e.g. alexandria/src/main/java/...).',
  '',
  'Style rules (from CONTRIBUTING and existing code):',
  '- Lombok @Getter @Setter @RequiredArgsConstructor; @Slf4j on services that log.',
  '- Records for DTOs. Constructor injection via @RequiredArgsConstructor.',
  '- Hand-rolled @Component mappers (match the style of UserMapper).',
  '- Java 25 / Spring Boot 4.0.6.',
  '- Bean Validation annotations on DTOs (@NotBlank/@Size/etc.) per spec section 7.',
  '- @Transactional on services; readOnly = true on getters.',
  '- Do not bypass OwnershipService and inline ownership checks.',
  '- Match the existing codes import order and whitespace (look at AuthService/UserMapper).',
  '',
  'Do NOT touch files outside your slices stated scope. If you need a type that another slice owns, just reference it by FQN — Phase B will apply slices in dependency order.',
  '',
  'When in doubt, the spec wins. If the spec is ambiguous, prefer the parent architecture spec, then existing code patterns.',
  '',
  'Final task: return ONLY the StructuredOutput. Do not commit, do not run mvn, do not edit anything outside your slice.',
]
const BRIEF = BRIEF_LINES.join('\n')

phase('Author')

const sliceExceptions = agent(BRIEF + `

## Slice: Exception hierarchy refactor

Owns:
- Create alexandria/src/main/java/com/alexandria/exception/NotFoundException.java — abstract base, errorCode() accessor, extends RuntimeException.
- Create alexandria/src/main/java/com/alexandria/exception/ForbiddenException.java — REPLACE existing concrete class with abstract base + errorCode().
- Create alexandria/src/main/java/com/alexandria/exception/ConflictException.java — abstract base.
- DELETE alexandria/src/main/java/com/alexandria/exception/ResourceNotFoundException.java.
- Modify DocumentNotFoundException.java — extend NotFoundException, errorCode "DOCUMENT_NOT_FOUND".
- Modify UserNotFoundException.java — extend NotFoundException, errorCode "USER_NOT_FOUND".
- Modify ReadingListNotFoundException.java — extend NotFoundException, errorCode "READING_LIST_NOT_FOUND".
- Modify EmailAlreadyInUseException.java — extend ConflictException, errorCode "EMAIL_TAKEN".
- Create CategoryNotFoundException.java — extends NotFoundException, errorCode "CATEGORY_NOT_FOUND".
- Create CategoryAlreadyExistsException.java — extends ConflictException, errorCode "CATEGORY_NAME_TAKEN".
- Create InvalidDocumentContentException.java — extends ConflictException, errorCode "INVALID_DOCUMENT_CONTENT".

The abstract bases each take a String message AND a String errorCode in the constructor; expose getErrorCode().

Existing call sites that throw or catch these exceptions stay AS-IS. Do NOT modify ReadingListService.java's "Item not found" line — another slice will fix it.

Spec section 12.

Return your patch.`, {
  label: 'exceptions',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceCommonDtos = agent(BRIEF + `

## Slice: PageResponse common DTO + UserSummary leak fix + UpdateUserRequest move

Owns:
- Create alexandria/src/main/java/com/alexandria/dto/common/PageResponse.java — record (List<T> content, int page, int size, long totalElements, int totalPages, boolean last) + static <T> PageResponse<T> of(Page<T> page) factory. Spec section 7.1.
- Create alexandria/src/main/java/com/alexandria/dto/user/UserSummary.java — record (UUID id, String displayName).
- Create alexandria/src/main/java/com/alexandria/dto/user/UpdateUserRequest.java — record with @Size(max=255) String displayName, @Size(min=8, max=255) String password.
- DELETE alexandria/src/main/java/com/alexandria/dto/UpdateUserRequest.java.
- Do NOT touch UserResponse.java yet.

Return your patch.`, {
  label: 'common-dtos',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceDocumentDtos = agent(BRIEF + `

## Slice: Document DTOs + Visibility enum + AuthorSummary + CategorySummary

Owns:
- Create alexandria/src/main/java/com/alexandria/entity/Visibility.java — enum { PUBLIC, PRIVATE }.
- Create alexandria/src/main/java/com/alexandria/dto/document/AuthorSummary.java — record (UUID id, String displayName).
- Create alexandria/src/main/java/com/alexandria/dto/document/CategorySummary.java — record (UUID id, String name).
- Create alexandria/src/main/java/com/alexandria/dto/document/CreateDocumentRequest.java — record per spec 7.2: @NotBlank @Size(max=255) String title, @Size(max=5000) String description, @NotBlank @Size(max=50) String type, Set<UUID> categoryIds, Visibility visibility.
- Create alexandria/src/main/java/com/alexandria/dto/document/CreateArticleRequest.java — record per spec 7.2.
- Create alexandria/src/main/java/com/alexandria/dto/document/UpdateDocumentRequest.java — record per spec 7.2 (no @NotBlank).
- Create alexandria/src/main/java/com/alexandria/dto/document/DocumentSummary.java — record per spec 7.2 (no body).
- Create alexandria/src/main/java/com/alexandria/dto/document/DocumentDetail.java — record per spec 7.2 (with body).
- DELETE alexandria/src/main/java/com/alexandria/dto/CreateDocumentRequest.java.
- DELETE alexandria/src/main/java/com/alexandria/dto/UpdateDocumentRequest.java.
- DELETE alexandria/src/main/java/com/alexandria/dto/DocumentResponse.java.
- DELETE alexandria/src/main/java/com/alexandria/dto/DocumentSummaryResponse.java.

CRITICAL: uploadedFilePath is NEVER exposed in any DTO. Use Set<UUID> not List<UUID>.

Return your patch.`, {
  label: 'document-dtos',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceDocumentEntity = agent(BRIEF + `

## Slice: Document entity additions per spec 5.1

Owns:
- Modify alexandria/src/main/java/com/alexandria/entity/Document.java — ADD fields:
  uploaded_file_path varchar(512) → String uploadedFilePath
  original_filename varchar(255) → String originalFilename
  content_type varchar(100) → String contentType
  size_bytes bigint → Long sizeBytes
  body text → String body (columnDefinition = "text")
  visibility varchar(20) NOT NULL DEFAULT PUBLIC → @Enumerated(EnumType.STRING) @Column(nullable = false) private Visibility visibility = Visibility.PUBLIC;
- Add @PrePersist @PreUpdate lifecycle methods. @PrePersist sets createdAt = updatedAt = Instant.now() and (if visibility null) Visibility.PUBLIC. @PreUpdate sets updatedAt = Instant.now().
- Keep existing fileUrl field.
- Reference com.alexandria.entity.Visibility (created by document-dtos slice).

Return your patch.`, {
  label: 'document-entity',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceLiquibase = agent(BRIEF + `

## Slice: Liquibase changesets per spec 4 + 8.2

Currently in alexandria/src/main/resources/db/changelog/changes/:
- 001-initial-schema.yaml
- 002-add-roles.yaml
- 003-seed-roles.yaml
- 004-seed-categories.yaml  (currently seeds Science/History/Technology — only 3, WRONG)

Per spec section 4:
- 004-extend-documents.yaml — adds columns to documents per 5.1
- 005-seed-categories.yaml — seeds 10 categories: Computer Science, Mathematics, Physics, Biology, Engineering, History, Literature, Philosophy, Economics, Other. Use valueComputed: gen_random_uuid().
- 006-fix-document-categories-fk.yaml — drop fk_doc_categories_category, re-add ON DELETE CASCADE.

You MUST:
- Create 004-extend-documents.yaml with addColumn for uploaded_file_path, original_filename, content_type, size_bytes, body, visibility (varchar(20) NOT NULL DEFAULT PUBLIC).
- DELETE the existing 004-seed-categories.yaml.
- Create 005-seed-categories.yaml with 10 inserts in order.
- Create 006-fix-document-categories-fk.yaml — dropForeignKeyConstraint + addForeignKeyConstraint with onDelete CASCADE. Constraint name: fk_doc_categories_category.
- Modify db.changelog-master.yaml — list 001/002/003/004-extend-documents/005-seed-categories/006-fix-document-categories-fk in order.

Author "alexandria"; ids "004", "005", "006".

Return your patch.`, {
  label: 'liquibase',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceFileStorage = agent(BRIEF + `

## Slice: FileStorageService + LocalFileStorageService + StorageProperties + props + gitignore

Spec section 11.

Owns:
- Create alexandria/src/main/java/com/alexandria/storage/FileStorageService.java — interface (StoredFile store(MultipartFile file); Resource load(String relativePath); void delete(String relativePath);).
- Create alexandria/src/main/java/com/alexandria/storage/StoredFile.java — record (String relativePath, String originalFilename, String contentType, long sizeBytes).
- Create alexandria/src/main/java/com/alexandria/storage/StorageProperties.java — @ConfigurationProperties(prefix = "app.storage") record (Path root, DataSize maxFileSize, Set<String> allowedContentTypes).
- Create alexandria/src/main/java/com/alexandria/storage/LocalFileStorageService.java — @Service @RequiredArgsConstructor @Slf4j. Implements FileStorageService. Sharded path "uuid[0:2]/uuid[2:4]/uuid.ext". Validate size and content type. Throw MaxUploadSizeExceededException for oversize, InvalidDocumentContentException for bad content type. Sanitize original filename. load() rejects path traversal. delete() best-effort with WARN.
- Create alexandria/src/main/java/com/alexandria/config/StorageConfig.java — @Configuration @EnableConfigurationProperties(StorageProperties.class).
- Modify alexandria/src/main/resources/application.properties — append:
    app.storage.root={APP_STORAGE_ROOT:./var/uploads}
    app.storage.max-file-size=50MB
    app.storage.allowed-content-types=application/pdf,text/plain,text/html,application/epub+zip
    spring.servlet.multipart.max-file-size=50MB
    spring.servlet.multipart.max-request-size=55MB
  (Use the literal Spring placeholder syntax with dollar-sign-curly when writing the file. The raw value is dollar-sign + APP_STORAGE_ROOT + colon-equals plus default. Just use the standard Spring property placeholder.)
- Modify alexandria/.gitignore — append "var/uploads/" line under a new section.

InvalidDocumentContentException is com.alexandria.exception.InvalidDocumentContentException (created by exceptions slice).

Return your patch.`, {
  label: 'file-storage',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceSecurityAndHandler = agent(BRIEF + `

## Slice: OwnershipService + extend GlobalExceptionHandler + SecurityConfig matchers + @EnableMethodSecurity

Spec sections 8, 9.4, 12.3, 13.

Owns:
- Create alexandria/src/main/java/com/alexandria/service/OwnershipService.java with @Component("ownership"), @RequiredArgsConstructor, three methods:
    isDocumentOwner(UUID documentId, UserDetails principal): finds the document, compares author email to principal username.
    isSelf(UUID userId, UserDetails principal): finds the user by id, compares email to principal username.
    isReadingListOwner(UUID listId, UserDetails principal): finds the reading list, compares user email to principal username.
  All return false when principal is null. UserDetails is org.springframework.security.core.userdetails.UserDetails.

- Modify alexandria/src/main/java/com/alexandria/exception/GlobalExceptionHandler.java — extend with new @ExceptionHandler methods, KEEPING existing ones:
    NotFoundException → 404
    ForbiddenException → 403
    ConflictException → 409 (catches CategoryAlreadyExistsException, EmailAlreadyInUseException, InvalidDocumentContentException). REMOVE the existing EmailAlreadyInUseException handler.
    org.springframework.security.access.AccessDeniedException → 403
    org.springframework.web.multipart.MaxUploadSizeExceededException → 413
    IllegalArgumentException → 400
  Keep MethodArgumentNotValidException (400), BadCredentialsException (401), generic Exception (500).

- Modify alexandria/src/main/java/com/alexandria/security/SecurityConfig.java:
    Replace the requestMatchers with:
      .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
      .requestMatchers(HttpMethod.GET, "/api/documents", "/api/documents/{id}", "/api/documents/{id}/file").permitAll()
      .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
      .requestMatchers(HttpMethod.GET, "/api/users/{id}").permitAll()
      .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
      .anyRequest().authenticated()
    Add @EnableMethodSecurity on the SecurityConfig class.

Return your patch.`, {
  label: 'security-and-handler',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceDocumentRepo = agent(BRIEF + `

## Slice: DocumentRepository (JpaSpecificationExecutor) + DocumentSpecifications

Spec section 6.

Owns:
- Modify alexandria/src/main/java/com/alexandria/repository/DocumentRepository.java to extend BOTH JpaRepository<Document, UUID> AND JpaSpecificationExecutor<Document>. Drop the existing @Query.
- Create alexandria/src/main/java/com/alexandria/repository/DocumentSpecifications.java — final utility class with private ctor and public static factories returning Specification<Document>:
    hasType(String type) — eq d.type, no-op when null
    hasCategory(UUID categoryId) — uses join on documentCategories.category.id, no-op when null
    hasAuthor(UUID authorId) — eq d.author.id, no-op when null
    titleContains(String search) — LOWER(d.title) LIKE LOWER('%search%'), no-op when null/blank
    isVisible(UUID currentUserId) — visibility = PUBLIC OR author.id = currentUserId. When currentUserId null, degrades to visibility = PUBLIC.

  No-op specs return (root, q, cb) -> cb.conjunction().

Return your patch.`, {
  label: 'document-repo',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceDocumentMapper = agent(BRIEF + `

## Slice: DocumentMapper rewrite

Spec section 7.2.

Owns:
- Modify alexandria/src/main/java/com/alexandria/mapper/DocumentMapper.java to expose:
    DocumentSummary toSummary(Document d) — uses AuthorSummary, Set<CategorySummary>, hasFile = uploadedFilePath != null, hasBody = body != null.
    DocumentDetail toDetail(Document d) — same plus body field.
  Drop the UserMapper dep. Use @Component, no other deps.

Return your patch.`, {
  label: 'document-mapper',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceDocumentEndpoint = agent(BRIEF + `

## Slice: DocumentService + DocumentController rewrite (multipart, article, streaming, visibility, sort whitelist)

Spec sections 8.1, 9.1, 10.1, 10.2.

Owns:
- Modify alexandria/src/main/java/com/alexandria/service/DocumentService.java — full rewrite:

  Public methods:
    DocumentDetail create(CreateDocumentRequest meta, MultipartFile file, UUID currentUserId)
    DocumentDetail createArticle(CreateArticleRequest req, UUID currentUserId)
    DocumentDetail update(UUID id, UpdateDocumentRequest req)
    void delete(UUID id, boolean isAdmin)
    DocumentDetail get(UUID id, UUID currentUserId)
    PageResponse<DocumentSummary> list(DocumentFilters filters, Pageable pageable, UUID currentUserId)
    StoredFileResource streamFile(UUID id, UUID currentUserId)

  Static nested records on DocumentService:
    DocumentFilters(String type, UUID categoryId, UUID authorId, String search)
    StoredFileResource(Resource resource, String contentType, String originalFilename, long sizeBytes)

  Internals:
    Inject DocumentRepository, CategoryRepository, UserRepository, FileStorageService, DocumentMapper.
    create(): validate categoryIds — for each id call categoryRepository.findById(...).orElseThrow(() -> new CategoryNotFoundException(id)) and collect. Find current user — userRepository.findById(currentUserId).orElseThrow(UserNotFoundException). storedFile = fileStorage.store(file). Set document fields incl. uploadedFilePath, originalFilename, contentType, sizeBytes from storedFile. Default visibility = PUBLIC if null. Set author. Build documentCategories (mutable ArrayList!). Save and toDetail.
    createArticle(): same but body, no fileStorage call.
    update(): null-skip pattern. For categoryIds: replace via clear() + addAll() — works because the collection is now seeded as ArrayList.
    delete(): load doc, capture path. Delete entity. Register after-commit cleanup via TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization { afterCommit(){ if(path!=null) try { fileStorage.delete(path); } catch (Exception e) { log.warn("Failed to delete file " + path, e); } } }).
    get(): if visibility == PRIVATE and (currentUserId == null || !doc.getAuthor().getId().equals(currentUserId)), throw DocumentNotFoundException(id). Else return detail.
    list(): build Specification chain via Specification.where(...).and(...).and(...). Always AND DocumentSpecifications.isVisible(currentUserId). pass pageable through. Map each Document to DocumentSummary. Wrap in PageResponse.of.
    streamFile(): same visibility check as get. resource = fileStorage.load(doc.getUploadedFilePath()). Return StoredFileResource(resource, doc.getContentType(), doc.getOriginalFilename(), doc.getSizeBytes() != null ? doc.getSizeBytes() : 0L).

  @Service @Transactional @RequiredArgsConstructor @Slf4j on the class. readOnly = true on get/list/streamFile.

- Modify alexandria/src/main/java/com/alexandria/controller/DocumentController.java — full rewrite per spec 8.1/10.1/10.2:

  @RestController @RequestMapping("/api/documents") @RequiredArgsConstructor.
  Inject DocumentService, SecurityUtils.
  ALLOWED_SORT = Set.of("createdAt", "updatedAt", "title").

  GET /: validates Pageable.getSort() — for each Order check ALLOWED_SORT.contains(order.getProperty()), else throw new IllegalArgumentException("Sort field not allowed: " + order.getProperty()). Resolves currentUserId from @AuthenticationPrincipal UserDetails (null if anonymous; else securityUtils.getCurrentUser().getId()). Returns PageResponse<DocumentSummary>.

  GET /{id}: same currentUserId logic. Returns DocumentDetail.

  POST / (multipart):
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DocumentDetail> create(
        @RequestPart("file") MultipartFile file,
        @RequestPart("metadata") @Valid CreateDocumentRequest metadata)
    Returns ResponseEntity.created(URI.create("/api/documents/" + detail.id())).body(detail).

  POST /article:
    @PostMapping("/article") @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DocumentDetail> createArticle(@Valid @RequestBody CreateArticleRequest req)
    Same Location header pattern.

  PUT /{id}: @PreAuthorize("@ownership.isDocumentOwner(#id, principal)") + body @Valid UpdateDocumentRequest req. Returns DocumentDetail.

  DELETE /{id}: @PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')") + @ResponseStatus(HttpStatus.NO_CONTENT). isAdmin computed via principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")).

  GET /{id}/file: returns ResponseEntity<Resource> with Content-Type from sfr.contentType(), Content-Length, Content-Disposition: inline; filename="<sanitized>". sanitize() replaces backslash, forward slash, double quote, and ASCII control chars with underscore; null name returns "file".

Return your patch.`, {
  label: 'document-endpoint',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceCategory = agent(BRIEF + `

## Slice: Category domain — repository methods, DTOs, mapper, service, controller

Spec sections 6, 7.3, 8.2, 9.2, 10.3.

Owns:
- Modify alexandria/src/main/java/com/alexandria/repository/CategoryRepository.java — add Optional<Category> findByName(String name); boolean existsByName(String name);
- Create alexandria/src/main/java/com/alexandria/dto/category/CreateCategoryRequest.java — record (@NotBlank @Size(max=255) String name).
- Create alexandria/src/main/java/com/alexandria/dto/category/UpdateCategoryRequest.java — record (@NotBlank @Size(max=255) String name).
- Create alexandria/src/main/java/com/alexandria/dto/category/CategoryResponse.java — record (UUID id, String name).
- DELETE alexandria/src/main/java/com/alexandria/dto/CategoryResponse.java.
- Create alexandria/src/main/java/com/alexandria/mapper/CategoryMapper.java — @Component, toResponse(Category)→CategoryResponse and fromCreate(CreateCategoryRequest)→Category.
- Create alexandria/src/main/java/com/alexandria/service/CategoryService.java — @Service @Transactional @RequiredArgsConstructor @Slf4j. list/create/update/delete. existsByName for dup detection (CategoryAlreadyExistsException). Lookup misses (CategoryNotFoundException).
- Create alexandria/src/main/java/com/alexandria/controller/CategoryController.java — @RestController @RequestMapping("/api/categories"). GET public; POST/PUT/DELETE @PreAuthorize("hasRole('ADMIN')"). POST returns 201 with Location. DELETE returns 204.

Return your patch.`, {
  label: 'category-domain',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceUserDomain = agent(BRIEF + `

## Slice: UserController/UserService/UserMapper switch to UserSummary; isSelf @PreAuthorize

Spec sections 7.4, 8.3, 9.3, 10.4.

Owns:
- Modify alexandria/src/main/java/com/alexandria/controller/UserController.java — drop SecurityUtils dep; GET returns UserSummary; PUT @PreAuthorize("@ownership.isSelf(#id, principal)") returns UserSummary.
- Modify alexandria/src/main/java/com/alexandria/service/UserService.java — drop the inline ForbiddenException check; signatures: get(UUID id) → UserSummary, update(UUID id, UpdateUserRequest req) → UserSummary. Logs at INFO when password changes.
- Modify alexandria/src/main/java/com/alexandria/mapper/UserMapper.java — ADD method: public UserSummary toSummary(User u) { return new UserSummary(u.getId(), u.getDisplayName()); }. KEEP existing toUser/toUserRole/toResponse methods.

Note UpdateUserRequest moves to dto/user/ (common-dtos slice). UserSummary lives in dto/user/.

Return your patch.`, {
  label: 'user-domain',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceReadingList = agent(BRIEF + `

## Slice: ReadingList alignment with OwnershipService + replace ResourceNotFoundException ref

Background: existing ReadingListService inlines ownership checks (4 sites) and throws com.alexandria.exception.ResourceNotFoundException at one site. ResourceNotFoundException is being deleted. We must keep ReadingList COMPILING.

Owns:
- Modify alexandria/src/main/java/com/alexandria/controller/ReadingListController.java:
    Add @PreAuthorize("@ownership.isReadingListOwner(#id, principal)") on getReadingList, updateReadingList, deleteReadingList, addItem, removeItem.
    Drop SecurityUtils.getCurrentUser() arg-passing into ownership-bound methods.
    Keep getReadingLists and createReadingList as-is.

- Modify alexandria/src/main/java/com/alexandria/service/ReadingListService.java:
    Remove the 4 inline ForbiddenException blocks.
    Drop User currentUser params from get/update/delete/addItem/removeItem.
    Keep User currentUser on createReadingList and getReadingLists.
    Replace ResourceNotFoundException at the "Item not found in reading list" site with new ReadingListItemNotFoundException(listId, docId).

- Create alexandria/src/main/java/com/alexandria/exception/ReadingListItemNotFoundException.java — extends NotFoundException, errorCode "READING_LIST_ITEM_NOT_FOUND". Constructor (UUID listId, UUID docId), message "Item not found in reading list: list=" + listId + ", doc=" + docId.

Return your patch.`, {
  label: 'reading-list',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceSpringdoc = agent(BRIEF + `

## Slice: springdoc-openapi dependency + OpenApiConfig

Spec section 14.

Owns:
- Modify alexandria/pom.xml — add inside <dependencies>:
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.8.17</version>
    </dependency>
  Place after spring-boot-starter-validation.

- Create alexandria/src/main/java/com/alexandria/config/OpenApiConfig.java with @OpenAPIDefinition (title "Alexandria API", version "0.1.0", description) + @SecurityScheme(name = "bearer-jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT"). Imports from io.swagger.v3.oas.annotations.

Return your patch.`, {
  label: 'springdoc',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceCompose = agent(BRIEF + `

## Slice: Docker Compose + .env.example at repo root

Spec section 15.

Owns:
- Create compose.yaml AT REPO ROOT (path "compose.yaml", NOT under alexandria/) per spec 15 verbatim.
- Create .env.example AT REPO ROOT with DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD/JWT_SECRET placeholders.

Return your patch.`, {
  label: 'compose',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const sliceDocsUpdate = agent(BRIEF + `

## Slice: docs/rest-api.md update

Spec section 17.

Owns:
- Modify docs/rest-api.md — replace contents with the high-level Phase 1 table from spec section 17 verbatim. Path "docs/rest-api.md" (relative to repo root).

Return your patch.`, {
  label: 'docs-update',
  schema: PATCH_SCHEMA,
  isolation: 'worktree',
})

const slices = await Promise.all([
  sliceExceptions, sliceCommonDtos, sliceDocumentDtos, sliceDocumentEntity,
  sliceLiquibase, sliceFileStorage, sliceSecurityAndHandler,
  sliceDocumentRepo, sliceDocumentMapper, sliceDocumentEndpoint,
  sliceCategory, sliceUserDomain, sliceReadingList,
  sliceSpringdoc, sliceCompose, sliceDocsUpdate,
])

const labels = [
  'exceptions', 'common-dtos', 'document-dtos', 'document-entity',
  'liquibase', 'file-storage', 'security-and-handler',
  'document-repo', 'document-mapper', 'document-endpoint',
  'category-domain', 'user-domain', 'reading-list',
  'springdoc', 'compose', 'docs-update',
]

const named = slices.map((s, i) => ({ label: labels[i], patch: s }))
const successful = named.filter(n => n.patch && n.patch.files)
log('Authored ' + successful.length + '/' + slices.length + ' slices')

phase('Apply')

const APPLY_ORDER = [
  'exceptions', 'common-dtos', 'document-dtos', 'document-entity',
  'document-repo', 'document-mapper',
  'liquibase', 'file-storage',
  'security-and-handler',
  'document-endpoint',
  'category-domain', 'user-domain', 'reading-list',
  'springdoc', 'compose', 'docs-update',
]

const orderedSlices = APPLY_ORDER
  .map(name => named.find(n => n.label === name))
  .filter(n => n && n.patch && n.patch.files)

const applyResult = await agent(
  'You are the apply driver running on the real working tree at ' + REPO + ' on branch feature/crud-endpoints.\n\n' +
  'Apply these slices IN ORDER, committing after each slice. Do NOT push. Do NOT run mvn.\n\n' +
  orderedSlices.map((s, i) => {
    return '=== Slice ' + (i + 1) + ': ' + s.label + ' ===\n' +
      'Summary: ' + (s.patch.summary || '(no summary)') + '\n' +
      (s.patch.notes ? 'Notes: ' + s.patch.notes + '\n' : '') +
      'Files (' + s.patch.files.length + '):\n' +
      s.patch.files.map(f => '  ' + f.action.toUpperCase() + ' ' + f.path).join('\n') + '\n\n' +
      'PATCH_PAYLOAD_JSON for slice "' + s.label + '":\n' +
      JSON.stringify(s.patch.files) + '\n'
  }).join('\n\n') +
  '\nProcess per slice:\n' +
  '1. For each file: if action=delete, run: rm -f ' + REPO + '/<path>. If create or modify, use the Write tool to write the FULL content provided.\n' +
  '2. Run cd ' + REPO + ' && git add -A && git status --short to see what changed.\n' +
  '3. Commit with: cd ' + REPO + ' && git commit -m "phase-1: <slice-label> — <one-line summary>"\n' +
  '4. If a Write fails (parent dir missing), run mkdir -p on the dir and retry once.\n\n' +
  'Stop on the first non-recoverable failure and report. Do NOT push.\n\n' +
  'Return: { applied: [{label, commit_sha, files_changed}], skipped: [{label, reason}], failed: [{label, reason}] }',
  {
    label: 'apply-driver',
    schema: {
      type: 'object',
      required: ['applied'],
      properties: {
        applied: {
          type: 'array',
          items: {
            type: 'object',
            required: ['label'],
            properties: {
              label: { type: 'string' },
              commit_sha: { type: 'string' },
              files_changed: { type: 'integer' },
            },
          },
        },
        skipped: { type: 'array' },
        failed: { type: 'array' },
      },
    },
  }
)

phase('Verify')

const verify = await agent(
  'You are running verification on ' + REPO + '/alexandria after the apply phase.\n\n' +
  'Run, in order, capturing stdout+stderr (truncate each to 200 lines max):\n' +
  '1. cd ' + REPO + '/alexandria && ./mvnw -q -DskipTests compile 2>&1 | tail -200\n' +
  '2. If compile passed: cd ' + REPO + '/alexandria && ./mvnw -q test 2>&1 | tail -200\n\n' +
  'Return a structured report. Do NOT modify any files. Do NOT commit. Do NOT push.',
  {
    label: 'verify',
    schema: {
      type: 'object',
      required: ['compile', 'summary'],
      properties: {
        compile: { enum: ['pass', 'fail'] },
        compile_output: { type: 'string' },
        tests: { enum: ['pass', 'fail', 'skipped'] },
        tests_output: { type: 'string' },
        summary: { type: 'string' },
      },
    },
  }
)

return {
  applied: applyResult ? applyResult.applied : [],
  skipped: applyResult ? applyResult.skipped : [],
  failed: applyResult ? applyResult.failed : [],
  verification: verify,
}
