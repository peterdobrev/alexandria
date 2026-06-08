package com.alexandria.service;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.CreateArticleRequest;
import com.alexandria.dto.document.CreateDocumentRequest;
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
import com.alexandria.repository.DocumentSpecifications;
import com.alexandria.repository.UserRepository;
import com.alexandria.storage.FileStorageService;
import com.alexandria.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Transactional
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final DocumentMapper documentMapper;

    public DocumentDetail create(CreateDocumentRequest meta, MultipartFile file, UUID currentUserId) {
        List<Category> categories = resolveCategories(meta.categoryIds());
        User author = userRepository.findById(currentUserId)
                .orElseThrow(() -> new UserNotFoundException(currentUserId));

        StoredFile storedFile = fileStorage.store(file);

        Document document = new Document();
        document.setTitle(meta.title());
        document.setDescription(meta.description());
        document.setType(meta.type());
        document.setUploadedFilePath(storedFile.relativePath());
        document.setOriginalFilename(storedFile.originalFilename());
        document.setContentType(storedFile.contentType());
        document.setSizeBytes(storedFile.sizeBytes());
        document.setVisibility(meta.visibility() != null ? meta.visibility() : Visibility.PUBLIC);
        document.setAuthor(author);
        document.setDocumentCategories(buildDocumentCategories(document, categories));

        Document saved = documentRepository.save(document);
        return documentMapper.toDetail(saved);
    }

    public DocumentDetail createArticle(CreateArticleRequest request, UUID currentUserId) {
        List<Category> categories = resolveCategories(request.categoryIds());
        User author = userRepository.findById(currentUserId)
                .orElseThrow(() -> new UserNotFoundException(currentUserId));

        Document document = new Document();
        document.setTitle(request.title());
        document.setDescription(request.description());
        document.setType(request.type());
        document.setBody(request.body());
        document.setVisibility(request.visibility() != null ? request.visibility() : Visibility.PUBLIC);
        document.setAuthor(author);
        document.setDocumentCategories(buildDocumentCategories(document, categories));

        Document saved = documentRepository.save(document);
        return documentMapper.toDetail(saved);
    }

    public DocumentDetail update(UUID id, UpdateDocumentRequest request) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        if (request.title() != null) {
            document.setTitle(request.title());
        }
        if (request.description() != null) {
            document.setDescription(request.description());
        }
        if (request.visibility() != null) {
            document.setVisibility(request.visibility());
        }
        if (request.categoryIds() != null) {
            List<Category> categories = resolveCategories(request.categoryIds());
            document.getDocumentCategories().clear();
            document.getDocumentCategories().addAll(buildDocumentCategories(document, categories));
        }

        Document saved = documentRepository.save(document);
        return documentMapper.toDetail(saved);
    }

    public void delete(UUID id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        final String path = document.getUploadedFilePath();
        documentRepository.delete(document);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (path != null) {
                    try {
                        fileStorage.delete(path);
                    } catch (Exception e) {
                        log.warn("Failed to delete file " + path, e);
                    }
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public DocumentDetail get(UUID id, UUID currentUserId) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (document.getVisibility() == Visibility.PRIVATE
                && (currentUserId == null || !document.getAuthor().getId().equals(currentUserId))) {
            throw new DocumentNotFoundException(id);
        }
        return documentMapper.toDetail(document);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentSummary> list(DocumentFilters filters, Pageable pageable, UUID currentUserId) {
        Specification<Document> visibilitySpec = currentUserId == null
                ? DocumentSpecifications.isPublic()
                : DocumentSpecifications.isVisibleToUser(currentUserId);
        Specification<Document> spec = Specification.where(visibilitySpec);

        if (filters.type() != null) {
            spec = spec.and(DocumentSpecifications.hasType(filters.type()));
        }
        if (filters.categoryId() != null) {
            spec = spec.and(DocumentSpecifications.hasCategory(filters.categoryId()));
        }
        if (filters.authorId() != null) {
            spec = spec.and(DocumentSpecifications.hasAuthor(filters.authorId()));
        }
        if (filters.search() != null && !filters.search().isBlank()) {
            spec = spec.and(DocumentSpecifications.titleContains(filters.search()));
        }

        Page<Document> page = documentRepository.findAll(spec, pageable);
        Page<DocumentSummary> mapped = page.map(documentMapper::toSummary);
        return PageResponse.of(mapped);
    }

    @Transactional(readOnly = true)
    public StoredFileResource streamFile(UUID id, UUID currentUserId) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (document.getVisibility() == Visibility.PRIVATE
                && (currentUserId == null || !document.getAuthor().getId().equals(currentUserId))) {
            throw new DocumentNotFoundException(id);
        }
        if (document.getUploadedFilePath() == null) {
            throw new DocumentNotFoundException(id);
        }
        Resource resource = fileStorage.load(document.getUploadedFilePath());
        long size = document.getSizeBytes() != null ? document.getSizeBytes() : 0L;
        return new StoredFileResource(resource, document.getContentType(), document.getOriginalFilename(), size);
    }

    private List<Category> resolveCategories(Set<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<Category> categories = new ArrayList<>(categoryIds.size());
        for (UUID categoryId : categoryIds) {
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new CategoryNotFoundException(categoryId));
            categories.add(category);
        }
        return categories;
    }

    private List<DocumentCategory> buildDocumentCategories(Document document, List<Category> categories) {
        List<DocumentCategory> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Category category : categories) {
            if (!seen.add(category.getId())) {
                continue;
            }
            DocumentCategory dc = new DocumentCategory();
            dc.setId(new DocumentCategoryId());
            dc.setDocument(document);
            dc.setCategory(category);
            result.add(dc);
        }
        return result;
    }

    public record DocumentFilters(String type, UUID categoryId, UUID authorId, String search) {}

    public record StoredFileResource(Resource resource, String contentType, String originalFilename, long sizeBytes) {}
}
