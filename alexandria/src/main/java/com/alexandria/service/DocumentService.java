package com.alexandria.service;

import com.alexandria.dto.CreateDocumentRequest;
import com.alexandria.dto.DocumentResponse;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.dto.UpdateDocumentRequest;
import com.alexandria.entity.Category;
import com.alexandria.entity.Document;
import com.alexandria.entity.DocumentCategory;
import com.alexandria.entity.DocumentCategoryId;
import com.alexandria.entity.User;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.ForbiddenException;
import com.alexandria.mapper.DocumentMapper;
import com.alexandria.repository.CategoryRepository;
import com.alexandria.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentMapper documentMapper;

    @Transactional(readOnly = true)
    public Page<DocumentSummaryResponse> getDocuments(String type, UUID categoryId, UUID authorId,
                                                      String search, Pageable pageable) {
        return documentRepository.findWithFilters(type, authorId, categoryId, search, pageable)
                .map(documentMapper::toSummaryResponse);
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID id) {
        return documentMapper.toResponse(documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id)));
    }

    public DocumentResponse createDocument(CreateDocumentRequest request, User author) {
        Document document = new Document();
        document.setTitle(request.title());
        document.setDescription(request.description());
        document.setType(request.type());
        document.setFileUrl(request.fileUrl());
        document.setAuthor(author);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        document.setDocumentCategories(buildDocumentCategories(document, request.categoryIds()));
        return documentMapper.toResponse(documentRepository.save(document));
    }

    public DocumentResponse updateDocument(UUID id, UpdateDocumentRequest request, User currentUser) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (!document.getAuthor().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to update this document");
        }
        if (request.title() != null) {
            document.setTitle(request.title());
        }
        if (request.description() != null) {
            document.setDescription(request.description());
        }
        if (request.categoryIds() != null) {
            document.getDocumentCategories().clear();
            document.getDocumentCategories().addAll(buildDocumentCategories(document, request.categoryIds()));
        }
        document.setUpdatedAt(Instant.now());
        return documentMapper.toResponse(documentRepository.save(document));
    }

    public void deleteDocument(UUID id, User currentUser) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (!document.getAuthor().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to delete this document");
        }
        documentRepository.delete(document);
    }

    private List<DocumentCategory> buildDocumentCategories(Document document, List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        List<Category> categories = categoryRepository.findAllById(categoryIds);
        return categories.stream()
                .map(category -> {
                    DocumentCategory dc = new DocumentCategory();
                    dc.setId(new DocumentCategoryId());
                    dc.setDocument(document);
                    dc.setCategory(category);
                    return dc;
                })
                .toList();
    }
}
