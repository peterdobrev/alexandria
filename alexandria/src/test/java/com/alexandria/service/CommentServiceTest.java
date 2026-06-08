package com.alexandria.service;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.dto.comment.CreateCommentRequest;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.entity.Comment;
import com.alexandria.entity.Document;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.CommentNotFoundException;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.ForbiddenException;
import com.alexandria.mapper.CommentMapper;
import com.alexandria.repository.CommentRepository;
import com.alexandria.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock private CommentRepository commentRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private CommentMapper commentMapper;

    private CommentService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new CommentService(commentRepository, documentRepository, commentMapper);
    }

    @Test
    void getComments_publicDocument_returnsPage() {
        UUID docId = UUID.randomUUID();
        Document doc = publicDoc(docId, "author@example.com");
        Comment comment = new Comment();
        CommentResponse response = commentResponse();

        when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(doc));
        when(commentRepository.findByDocumentId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comment)));
        when(commentMapper.toResponse(any(Comment.class))).thenReturn(response);

        Page<CommentResponse> result = classUnderTest.getComments(docId, null, Pageable.unpaged());

        assertThat(result.getContent()).containsExactly(response);
    }

    @Test
    void getComments_privateDocument_ownerCanView() {
        UUID docId = UUID.randomUUID();
        String ownerEmail = "owner@example.com";
        Document doc = privateDoc(docId, ownerEmail);
        Comment comment = new Comment();
        CommentResponse response = commentResponse();

        when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(doc));
        when(commentRepository.findByDocumentId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comment)));
        when(commentMapper.toResponse(any(Comment.class))).thenReturn(response);

        Page<CommentResponse> result = classUnderTest.getComments(docId, ownerEmail, Pageable.unpaged());

        assertThat(result.getContent()).containsExactly(response);
    }

    @Test
    void getComments_privateDocument_nonOwnerThrowsForbidden() {
        UUID docId = UUID.randomUUID();
        Document doc = privateDoc(docId, "owner@example.com");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.getComments(docId, "other@example.com", Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class);

        verify(commentRepository, never()).findByDocumentId(any(), any());
    }

    @Test
    void getComments_unknownDocument_throwsDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.getComments(docId, null, Pageable.unpaged()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void addComment_publicDocument_savesAndReturnsResponse() {
        UUID docId = UUID.randomUUID();
        Document doc = publicDoc(docId, "author@example.com");
        User currentUser = userWithEmail("commenter@example.com");
        CreateCommentRequest request = new CreateCommentRequest("Nice doc!");
        Comment saved = new Comment();
        CommentResponse response = commentResponse();

        when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.of(doc));
        when(commentRepository.save(any(Comment.class))).thenReturn(saved);
        when(commentMapper.toResponse(any(Comment.class))).thenReturn(response);

        CommentResponse result = classUnderTest.addComment(docId, request, currentUser);

        assertThat(result).isEqualTo(response);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        Comment toSave = captor.getValue();
        assertThat(toSave.getDocument()).isSameAs(doc);
        assertThat(toSave.getAuthor()).isSameAs(currentUser);
        assertThat(toSave.getBody()).isEqualTo("Nice doc!");
    }

    @Test
    void addComment_unknownDocument_throwsDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.addComment(
                docId, new CreateCommentRequest("body"), userWithEmail("u@example.com")))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void addComment_privateDocumentNotOwner_throwsForbidden() {
        UUID docId = UUID.randomUUID();
        Document doc = privateDoc(docId, "owner@example.com");
        User commenter = userWithEmail("other@example.com");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.addComment(
                docId, new CreateCommentRequest("body"), commenter))
                .isInstanceOf(ForbiddenException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void deleteComment_existingCommentOnDocument_deletes() {
        UUID docId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        Document doc = publicDoc(docId, "author@example.com");
        Comment comment = new Comment();
        comment.setDocument(doc);

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        classUnderTest.deleteComment(docId, commentId);

        verify(commentRepository).delete(comment);
    }

    @Test
    void deleteComment_unknownComment_throwsCommentNotFoundException() {
        UUID docId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        when(commentRepository.findById(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.deleteComment(docId, commentId))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRepository, never()).delete(any());
    }

    @Test
    void deleteComment_commentBelongsToDifferentDocument_throwsCommentNotFoundException() {
        UUID docId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        Document otherDoc = publicDoc(UUID.randomUUID(), "author@example.com");
        Comment comment = new Comment();
        comment.setDocument(otherDoc);

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> classUnderTest.deleteComment(docId, commentId))
                .isInstanceOf(CommentNotFoundException.class);

        verify(commentRepository, never()).delete(any());
    }

    private static Document publicDoc(UUID id, String authorEmail) {
        User author = new User();
        author.setEmail(authorEmail);
        Document doc = new Document();
        doc.setId(id);
        doc.setAuthor(author);
        doc.setVisibility(Visibility.PUBLIC);
        return doc;
    }

    private static Document privateDoc(UUID id, String authorEmail) {
        User author = new User();
        author.setEmail(authorEmail);
        Document doc = new Document();
        doc.setId(id);
        doc.setAuthor(author);
        doc.setVisibility(Visibility.PRIVATE);
        return doc;
    }

    private static User userWithEmail(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        return user;
    }

    private static CommentResponse commentResponse() {
        return new CommentResponse(
                UUID.randomUUID(),
                new AuthorSummary(UUID.randomUUID(), "Alice"),
                "Nice doc!",
                Instant.now()
        );
    }
}
