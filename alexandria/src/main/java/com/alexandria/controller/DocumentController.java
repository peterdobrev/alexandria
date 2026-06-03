package com.alexandria.controller;

import com.alexandria.dto.CreateDocumentRequest;
import com.alexandria.dto.DocumentResponse;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.dto.UpdateDocumentRequest;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<Page<DocumentSummaryResponse>> getDocuments(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        return ResponseEntity.ok(documentService.getDocuments(type, categoryId, authorId, search, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocument(@PathVariable UUID id) {
        return ResponseEntity.ok(documentService.getDocument(id));
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public DocumentResponse createDocument(@Valid @RequestBody CreateDocumentRequest request) {
        return documentService.createDocument(request, securityUtils.getCurrentUser());
    }

    @PutMapping("/{id}")
    public ResponseEntity<DocumentResponse> updateDocument(@PathVariable UUID id,
                                                           @Valid @RequestBody UpdateDocumentRequest request) {
        return ResponseEntity.ok(documentService.updateDocument(id, request, securityUtils.getCurrentUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        documentService.deleteDocument(id, securityUtils.getCurrentUser());
        return ResponseEntity.noContent().build();
    }
}
