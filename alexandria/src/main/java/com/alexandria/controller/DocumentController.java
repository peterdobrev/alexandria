package com.alexandria.controller;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.CreateArticleRequest;
import com.alexandria.dto.document.CreateDocumentRequest;
import com.alexandria.dto.document.DocumentDetail;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.dto.document.UpdateDocumentRequest;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private static final Set<String> ALLOWED_SORT = Set.of("createdAt", "updatedAt", "title");

    private final DocumentService documentService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public PageResponse<DocumentSummary> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) String search,
            Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {
        validateSort(pageable.getSort());
        UUID currentUserId = currentUserId(principal);
        DocumentService.DocumentFilters filters =
                new DocumentService.DocumentFilters(type, categoryId, authorId, search);
        return documentService.list(filters, pageable, currentUserId);
    }

    @GetMapping("/{id}")
    public DocumentDetail get(@PathVariable UUID id,
                              @AuthenticationPrincipal UserDetails principal) {
        UUID currentUserId = currentUserId(principal);
        return documentService.get(id, currentUserId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DocumentDetail> create(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") @Valid CreateDocumentRequest metadata) {
        UUID currentUserId = securityUtils.getCurrentUser().getId();
        DocumentDetail detail = documentService.create(metadata, file, currentUserId);
        return ResponseEntity
                .created(URI.create("/api/documents/" + detail.id()))
                .body(detail);
    }

    @PostMapping("/article")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DocumentDetail> createArticle(@Valid @RequestBody CreateArticleRequest req) {
        UUID currentUserId = securityUtils.getCurrentUser().getId();
        DocumentDetail detail = documentService.createArticle(req, currentUserId);
        return ResponseEntity
                .created(URI.create("/api/documents/" + detail.id()))
                .body(detail);
    }

    @PreAuthorize("@ownership.isDocumentOwner(#id, principal)")
    @PutMapping("/{id}")
    public DocumentDetail update(@PathVariable UUID id,
                                 @Valid @RequestBody UpdateDocumentRequest req) {
        return documentService.update(id, req);
    }

    @PreAuthorize("@ownership.isDocumentOwner(#id, principal) or hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @AuthenticationPrincipal UserDetails principal) {
        boolean isAdmin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        documentService.delete(id, isAdmin);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> streamFile(@PathVariable UUID id,
                                               @AuthenticationPrincipal UserDetails principal) {
        UUID currentUserId = currentUserId(principal);
        DocumentService.StoredFileResource sfr = documentService.streamFile(id, currentUserId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(sfr.contentType()))
                .contentLength(sfr.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + sanitize(sfr.originalFilename()) + "\"")
                .body(sfr.resource());
    }

    private UUID currentUserId(UserDetails principal) {
        if (principal == null) {
            return null;
        }
        return securityUtils.getCurrentUser().getId();
    }

    private void validateSort(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return;
        }
        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT.contains(order.getProperty())) {
                throw new IllegalArgumentException("Sort field not allowed: " + order.getProperty());
            }
        }
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "file";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == '/' || c == '"' || c < 0x20 || c == 0x7F) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
