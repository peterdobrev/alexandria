package com.alexandria.service;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.CategorySummary;
import com.alexandria.dto.document.CreateArticleRequest;
import com.alexandria.dto.document.DocumentDetail;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.dto.document.UpdateDocumentRequest;
import com.alexandria.entity.Category;
import com.alexandria.entity.Document;
import com.alexandria.entity.DocumentCategory;
import com.alexandria.entity.DocumentCategoryId;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.CategoryNotFoundException;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.UserNotFoundException;
import com.alexandria.mapper.DocumentMapper;
import com.alexandria.repository.CategoryRepository;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.storage.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FileStorageService fileStorage;
    @Mock
    private DocumentMapper documentMapper;

    private DocumentService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new DocumentService(
                documentRepository,
                categoryRepository,
                userRepository,
                fileStorage,
                documentMapper
        );
    }

    // ---------- createArticle ----------

    @Test
    void createArticle_validRequest_savesDocumentAndReturnsDetail() {
        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        CreateArticleRequest request = new CreateArticleRequest(
                "How to test",
                "A short description",
                "ARTICLE",
                "Body of the article",
                Set.of(categoryId),
                null
        );

        User author = new User();
        author.setId(userId);
        author.setDisplayName("Author");

        Category category = new Category();
        category.setId(categoryId);
        category.setName("Cat");

        DocumentDetail expected = sampleDetail(userId);

        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(author));
        when(categoryRepository.findById(any(UUID.class))).thenReturn(Optional.of(category));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentMapper.toDetail(any(Document.class))).thenReturn(expected);

        DocumentDetail result = classUnderTest.createArticle(request, userId);

        assertThat(result).isSameAs(expected);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        Document saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("How to test");
        assertThat(saved.getDescription()).isEqualTo("A short description");
        assertThat(saved.getType()).isEqualTo("ARTICLE");
        assertThat(saved.getBody()).isEqualTo("Body of the article");
        // visibility defaults to PUBLIC when not provided
        assertThat(saved.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(saved.getAuthor()).isSameAs(author);
        assertThat(saved.getDocumentCategories()).hasSize(1);
        assertThat(saved.getDocumentCategories().get(0).getCategory()).isSameAs(category);
    }

    @Test
    void createArticle_unknownCategory_throwsCategoryNotFoundException() {
        UUID userId = UUID.randomUUID();
        UUID missingCategoryId = UUID.randomUUID();
        CreateArticleRequest request = new CreateArticleRequest(
                "Title", null, "ARTICLE", "Body",
                Set.of(missingCategoryId), Visibility.PUBLIC
        );

        when(categoryRepository.findById(missingCategoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.createArticle(request, userId))
                .isInstanceOf(CategoryNotFoundException.class)
                .hasMessageContaining(missingCategoryId.toString());

        verify(documentRepository, never()).save(any());
    }

    @Test
    void createArticle_unknownUser_throwsUserNotFoundException() {
        UUID userId = UUID.randomUUID();
        CreateArticleRequest request = new CreateArticleRequest(
                "Title", null, "ARTICLE", "Body",
                Set.of(), Visibility.PUBLIC
        );

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.createArticle(request, userId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(userId.toString());

        verify(documentRepository, never()).save(any());
    }

    // ---------- get ----------

    @Test
    void get_publicDocument_returnsDetail() {
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Document doc = publicDocument(docId, UUID.randomUUID());
        DocumentDetail expected = sampleDetail(docId);

        when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(doc));
        when(documentMapper.toDetail(any(Document.class))).thenReturn(expected);

        DocumentDetail result = classUnderTest.get(docId, userId);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void get_privateDocument_unauthenticatedCaller_throwsDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        Document doc = privateDocument(docId, UUID.randomUUID());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.get(docId, null))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(docId.toString());
    }

    @Test
    void get_privateDocument_otherCaller_throwsDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Document doc = privateDocument(docId, authorId);

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.get(docId, otherUserId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(docId.toString());
    }

    @Test
    void get_privateDocument_authorCaller_returnsDetail() {
        UUID docId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Document doc = privateDocument(docId, authorId);
        DocumentDetail expected = sampleDetail(docId);

        when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(doc));
        when(documentMapper.toDetail(any(Document.class))).thenReturn(expected);

        DocumentDetail result = classUnderTest.get(docId, authorId);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void get_unknownDocument_throwsDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.get(docId, UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(docId.toString());
    }

    // ---------- list ----------

    @Test
    void list_returnsPageResponseFromMappedSummaries() {
        UUID currentUserId = UUID.randomUUID();
        DocumentService.DocumentFilters filters =
                new DocumentService.DocumentFilters(null, null, null, null);
        Pageable pageable = PageRequest.of(0, 20);

        Document doc = new Document();
        doc.setId(UUID.randomUUID());
        DocumentSummary summary = sampleSummary(doc.getId());

        Page<Document> page = new PageImpl<>(List.of(doc), pageable, 1);

        when(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(documentMapper.toSummary(doc)).thenReturn(summary);

        PageResponse<DocumentSummary> response = classUnderTest.list(filters, pageable, currentUserId);

        assertThat(response.content()).containsExactly(summary);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.last()).isTrue();
    }

    // ---------- update ----------

    @Test
    void update_retainsOverlappingCategory_withoutRemovingAndReinsertingIt() {
        UUID docId = UUID.randomUUID();
        UUID keptCategoryId = UUID.randomUUID();
        UUID addedCategoryId = UUID.randomUUID();

        Category kept = category(keptCategoryId);
        Category added = category(addedCategoryId);

        Document document = publicDocument(docId, UUID.randomUUID());
        DocumentCategory existingAssociation = new DocumentCategory();
        existingAssociation.setId(new DocumentCategoryId(docId, keptCategoryId));
        existingAssociation.setDocument(document);
        existingAssociation.setCategory(kept);
        document.setDocumentCategories(new ArrayList<>(List.of(existingAssociation)));

        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(categoryRepository.findById(keptCategoryId)).thenReturn(Optional.of(kept));
        when(categoryRepository.findById(addedCategoryId)).thenReturn(Optional.of(added));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentMapper.toDetail(any(Document.class))).thenReturn(sampleDetail(docId));

        classUnderTest.update(docId, new UpdateDocumentRequest(
                null, null, Set.of(keptCategoryId, addedCategoryId), null));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        List<DocumentCategory> associations = captor.getValue().getDocumentCategories();

        // The already-present association must be left in place (not cleared and
        // re-created) — that is what avoids the duplicate composite-key flush error.
        assertThat(associations).contains(existingAssociation);
        assertThat(associations)
                .extracting(dc -> dc.getCategory().getId())
                .containsExactlyInAnyOrder(keptCategoryId, addedCategoryId);
    }

    @Test
    void update_removesCategoriesNoLongerRequested() {
        UUID docId = UUID.randomUUID();
        UUID removedCategoryId = UUID.randomUUID();
        UUID keptCategoryId = UUID.randomUUID();

        Category removed = category(removedCategoryId);
        Category kept = category(keptCategoryId);

        Document document = publicDocument(docId, UUID.randomUUID());
        DocumentCategory removedAssociation = new DocumentCategory();
        removedAssociation.setId(new DocumentCategoryId(docId, removedCategoryId));
        removedAssociation.setDocument(document);
        removedAssociation.setCategory(removed);
        DocumentCategory keptAssociation = new DocumentCategory();
        keptAssociation.setId(new DocumentCategoryId(docId, keptCategoryId));
        keptAssociation.setDocument(document);
        keptAssociation.setCategory(kept);
        document.setDocumentCategories(new ArrayList<>(List.of(removedAssociation, keptAssociation)));

        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(categoryRepository.findById(keptCategoryId)).thenReturn(Optional.of(kept));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentMapper.toDetail(any(Document.class))).thenReturn(sampleDetail(docId));

        classUnderTest.update(docId, new UpdateDocumentRequest(
                null, null, Set.of(keptCategoryId), null));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getDocumentCategories())
                .extracting(dc -> dc.getCategory().getId())
                .containsExactly(keptCategoryId);
    }

    // ---------- delete ----------

    @AfterEach
    void tearDownTransactionSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void delete_existingDocument_invokesRepositoryDelete() {
        UUID docId = UUID.randomUUID();
        Document doc = publicDocument(docId, UUID.randomUUID());
        doc.setUploadedFilePath("uploads/file.pdf");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        // The service registers an after-commit synchronization. Initialize a
        // synchronization context so that registration does not blow up; we do
        // not assert what the callback does — that is out of scope here.
        TransactionSynchronizationManager.initSynchronization();
        try {
            classUnderTest.delete(docId);
        } finally {
            TransactionSynchronizationManager.clear();
        }

        verify(documentRepository).delete(doc);
    }

    @Test
    void delete_unknownDocument_throwsDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.delete(docId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(docId.toString());

        verify(documentRepository, never()).delete(any(Document.class));
    }

    // ---------- helpers ----------

    private Document publicDocument(UUID id, UUID authorId) {
        return buildDocument(id, authorId, Visibility.PUBLIC);
    }

    private Document privateDocument(UUID id, UUID authorId) {
        return buildDocument(id, authorId, Visibility.PRIVATE);
    }

    private Document buildDocument(UUID id, UUID authorId, Visibility visibility) {
        Document doc = new Document();
        doc.setId(id);
        doc.setTitle("Some title");
        doc.setType("ARTICLE");
        doc.setVisibility(visibility);
        User author = new User();
        author.setId(authorId);
        author.setDisplayName("Author");
        doc.setAuthor(author);
        return doc;
    }

    private Category category(UUID id) {
        Category category = new Category();
        category.setId(id);
        category.setName("Category " + id);
        return category;
    }

    private DocumentDetail sampleDetail(UUID id) {
        return new DocumentDetail(
                id,
                "Some title",
                "Description",
                "ARTICLE",
                Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.<CategorySummary>of(),
                false,
                true,
                0L,
                null,
                "Body",
                Instant.now(),
                Instant.now()
        );
    }

    private DocumentSummary sampleSummary(UUID id) {
        return new DocumentSummary(
                id,
                "Some title",
                "Description",
                "ARTICLE",
                Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.<CategorySummary>of(),
                false,
                true,
                0L,
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
