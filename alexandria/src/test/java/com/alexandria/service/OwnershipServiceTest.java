package com.alexandria.service;

import com.alexandria.entity.Comment;
import com.alexandria.entity.User;
import com.alexandria.repository.CommentRepository;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListRepository;
import com.alexandria.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnershipServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReadingListRepository readingListRepository;
    @Mock private CommentRepository commentRepository;

    private OwnershipService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new OwnershipService(
                documentRepository, userRepository, readingListRepository, commentRepository);
    }

    @Test
    void isCommentOwnerOrAdmin_commentOwner_returnsTrue() {
        UUID commentId = UUID.randomUUID();
        String email = "alice@example.com";

        User author = new User();
        author.setEmail(email);

        Comment comment = new Comment();
        comment.setAuthor(author);
        comment.setId(commentId);

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        var principal = org.springframework.security.core.userdetails.User
                .withUsername(email).password("x").authorities("ROLE_USER").build();

        assertThat(classUnderTest.isCommentOwnerOrAdmin(commentId, principal)).isTrue();
    }

    @Test
    void isCommentOwnerOrAdmin_adminNotOwner_returnsTrue() {
        UUID commentId = UUID.randomUUID();
        String adminEmail = "admin@example.com";

        var principal = org.springframework.security.core.userdetails.User
                .withUsername(adminEmail).password("x").authorities("ROLE_ADMIN").build();

        assertThat(classUnderTest.isCommentOwnerOrAdmin(commentId, principal)).isTrue();
    }

    @Test
    void isCommentOwnerOrAdmin_otherUser_returnsFalse() {
        UUID commentId = UUID.randomUUID();
        String ownerEmail = "owner@example.com";
        String otherEmail = "other@example.com";

        User owner = new User();
        owner.setEmail(ownerEmail);

        Comment comment = new Comment();
        comment.setAuthor(owner);
        comment.setId(commentId);

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        var principal = org.springframework.security.core.userdetails.User
                .withUsername(otherEmail).password("x").authorities("ROLE_USER").build();

        assertThat(classUnderTest.isCommentOwnerOrAdmin(commentId, principal)).isFalse();
    }

    @Test
    void isCommentOwnerOrAdmin_nullPrincipal_returnsFalse() {
        assertThat(classUnderTest.isCommentOwnerOrAdmin(UUID.randomUUID(), null)).isFalse();
    }
}
