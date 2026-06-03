package com.alexandria.service;

import com.alexandria.dto.CreateDocumentRequest;
import com.alexandria.dto.DocumentResponse;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.dto.UpdateDocumentRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.Document;
import com.alexandria.entity.User;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.ForbiddenException;
import com.alexandria.mapper.DocumentMapper;
import com.alexandria.repository.CategoryRepository;
import com.alexandria.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private DocumentMapper documentMapper;

    private DocumentService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new DocumentService(documentRepository, categoryRepository, documentMapper);
    }

    @Test
    void getDocuments_returnsPageOfSummaries() {
        Document doc = new Document();
        DocumentSummaryResponse summary = documentSummary();
        Page<Document> page = new PageImpl<>(List.of(doc));

        when(documentRepository.findWithFilters(null, null, null, null, Pageable.unpaged())).thenReturn(page);
        when(documentMapper.toSummaryResponse(doc)).thenReturn(summary);

        Page<DocumentSummaryResponse> result = classUnderTest.getDocuments(null, null, null, null, Pageable.unpaged());

        assertThat(result.getContent()).containsExactly(summary);
    }

    @Test
    void getDocument_existingDocument_returnsDocumentResponse() {
        UUID id = UUID.randomUUID();
        Document doc = new Document();
        DocumentResponse response = documentResponse(id);

        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(documentMapper.toResponse(doc)).thenReturn(response);

        assertThat(classUnderTest.getDocument(id)).isEqualTo(response);
    }

    @Test
    void getDocument_nonExistentDocument_throwsDocumentNotFoundException() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.getDocument(id))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void createDocument_validRequest_savesAndReturnsDocumentResponse() {
        User author = new User();
        author.setId(UUID.randomUUID());
        CreateDocumentRequest request = new CreateDocumentRequest("Title", "Desc", "PDF", "http://url", List.of());
        Document saved = new Document();
        DocumentResponse response = documentResponse(UUID.randomUUID());

        when(documentRepository.save(any(Document.class))).thenReturn(saved);
        when(documentMapper.toResponse(saved)).thenReturn(response);

        assertThat(classUnderTest.createDocument(request, author)).isEqualTo(response);
        verify(documentRepository).save(any(Document.class));
    }

    @Test
    void updateDocument_owner_updatesAndReturnsDocumentResponse() {
        UUID id = UUID.randomUUID();
        User owner = new User();
        owner.setId(UUID.randomUUID());
        Document doc = new Document();
        doc.setAuthor(owner);
        doc.setDocumentCategories(new ArrayList<>());
        UpdateDocumentRequest request = new UpdateDocumentRequest("New Title", null, null);
        DocumentResponse response = documentResponse(id);

        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);
        when(documentMapper.toResponse(doc)).thenReturn(response);

        assertThat(classUnderTest.updateDocument(id, request, owner)).isEqualTo(response);
        assertThat(doc.getTitle()).isEqualTo("New Title");
    }

    @Test
    void updateDocument_notOwner_throwsForbiddenException() {
        UUID id = UUID.randomUUID();
        User owner = new User();
        owner.setId(UUID.randomUUID());
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        Document doc = new Document();
        doc.setAuthor(owner);

        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.updateDocument(id, new UpdateDocumentRequest(null, null, null), otherUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deleteDocument_owner_deletesDocument() {
        UUID id = UUID.randomUUID();
        User owner = new User();
        owner.setId(UUID.randomUUID());
        Document doc = new Document();
        doc.setAuthor(owner);

        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        classUnderTest.deleteDocument(id, owner);

        verify(documentRepository).delete(doc);
    }

    @Test
    void deleteDocument_notOwner_throwsForbiddenException() {
        UUID id = UUID.randomUUID();
        User owner = new User();
        owner.setId(UUID.randomUUID());
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        Document doc = new Document();
        doc.setAuthor(owner);

        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.deleteDocument(id, otherUser))
                .isInstanceOf(ForbiddenException.class);
    }

    private DocumentSummaryResponse documentSummary() {
        return new DocumentSummaryResponse(UUID.randomUUID(), "Title", "PDF",
                new UserResponse(UUID.randomUUID(), "author@test.com", "Author", Instant.now()),
                List.of(), Instant.now());
    }

    private DocumentResponse documentResponse(UUID id) {
        return new DocumentResponse(id, "Title", "Desc", "PDF", "http://url",
                new UserResponse(UUID.randomUUID(), "author@test.com", "Author", Instant.now()),
                List.of(), Instant.now(), Instant.now());
    }
}
