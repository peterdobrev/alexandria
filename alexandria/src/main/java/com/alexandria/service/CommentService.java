package com.alexandria.service;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.dto.comment.CreateCommentRequest;
import com.alexandria.entity.Comment;
import com.alexandria.entity.Document;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.AccessForbiddenException;
import com.alexandria.exception.CommentNotFoundException;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.mapper.CommentMapper;
import com.alexandria.repository.CommentRepository;
import com.alexandria.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Transactional
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final DocumentRepository documentRepository;
    private final CommentMapper commentMapper;

    @Transactional(readOnly = true)
    public Page<CommentResponse> getComments(UUID documentId, String currentUserEmail, Pageable pageable) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        assertVisible(document, currentUserEmail);
        return commentRepository.findByDocumentId(documentId, pageable)
                .map(commentMapper::toResponse);
    }

    public CommentResponse addComment(UUID documentId, CreateCommentRequest request, User currentUser) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        assertVisible(document, currentUser.getEmail());
        Comment comment = new Comment();
        comment.setDocument(document);
        comment.setAuthor(currentUser);
        comment.setBody(request.body());
        return commentMapper.toResponse(commentRepository.save(comment));
    }

    public void deleteComment(UUID documentId, UUID commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentNotFoundException(commentId));
        if (!comment.getDocument().getId().equals(documentId)) {
            throw new CommentNotFoundException(commentId);
        }
        commentRepository.delete(comment);
    }

    private void assertVisible(Document document, String currentUserEmail) {
        if (document.getVisibility() == Visibility.PUBLIC) {
            return;
        }
        if (currentUserEmail != null && currentUserEmail.equals(document.getAuthor().getEmail())) {
            return;
        }
        throw new AccessForbiddenException("Access denied");
    }
}
