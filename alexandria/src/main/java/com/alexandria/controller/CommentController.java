package com.alexandria.controller;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.dto.comment.CreateCommentRequest;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents/{documentId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<Page<CommentResponse>> getComments(
            @PathVariable UUID documentId,
            Authentication authentication,
            Pageable pageable) {
        String currentUserEmail = resolveCurrentUserEmail(authentication).orElse(null);
        return ResponseEntity.ok(commentService.getComments(documentId, currentUserEmail, pageable));
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public CommentResponse addComment(
            @PathVariable UUID documentId,
            @Valid @RequestBody CreateCommentRequest request) {
        return commentService.addComment(documentId, request, securityUtils.getCurrentUser());
    }

    @PreAuthorize("@ownership.isCommentOwnerOrAdmin(#commentId, principal)")
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID documentId,
            @PathVariable UUID commentId) {
        commentService.deleteComment(documentId, commentId);
        return ResponseEntity.noContent().build();
    }

    private Optional<String> resolveCurrentUserEmail(Authentication authentication) {
        if (authentication == null) {
            return Optional.empty();
        }
        return Optional.of(authentication.getName());
    }
}
