package com.alexandria.service;

import com.alexandria.entity.Document;
import com.alexandria.entity.InteractionKind;
import com.alexandria.entity.User;
import com.alexandria.entity.UserDocumentInteraction;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.InteractionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InteractionServiceTest {

    @Mock private InteractionRepository interactionRepository;
    @Mock private DocumentRepository documentRepository;

    private InteractionService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new InteractionService(interactionRepository, documentRepository);
    }

    // --- logView ---

    @Test
    void given_publicDocumentAndNoRecentView_when_logView_then_persistsInteractionWithKindView() {
        User user = userWithId();
        UUID docId = UUID.randomUUID();
        Document doc = publicDoc(docId, "author@example.com");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(interactionRepository.existsRecent(eq(user.getId()), eq(docId), eq(InteractionKind.VIEW), any(Instant.class)))
                .thenReturn(false);

        classUnderTest.logView(user, docId);

        ArgumentCaptor<UserDocumentInteraction> captor = ArgumentCaptor.forClass(UserDocumentInteraction.class);
        verify(interactionRepository).save(captor.capture());
        UserDocumentInteraction toSave = captor.getValue();
        assertThat(toSave.getUser()).isSameAs(user);
        assertThat(toSave.getDocument()).isSameAs(doc);
        assertThat(toSave.getKind()).isEqualTo(InteractionKind.VIEW);
    }

    @Test
    void given_recentViewWithinFiveMinutes_when_logView_then_isNoOpAndDoesNotPersist() {
        User user = userWithId();
        UUID docId = UUID.randomUUID();
        Document doc = publicDoc(docId, "author@example.com");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(interactionRepository.existsRecent(eq(user.getId()), eq(docId), eq(InteractionKind.VIEW), any(Instant.class)))
                .thenReturn(true);

        classUnderTest.logView(user, docId);

        verify(interactionRepository, never()).save(any());
    }

    @Test
    void given_unknownDocument_when_logView_then_throwsDocumentNotFoundException() {
        User user = userWithId();
        UUID docId = UUID.randomUUID();

        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.logView(user, docId))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(interactionRepository, never()).save(any());
    }

    @Test
    void given_privateDocumentNotOwned_when_logView_then_throwsDocumentNotFoundException() {
        User user = userWithId();
        UUID docId = UUID.randomUUID();
        Document doc = privateDoc(docId, UUID.randomUUID());

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> classUnderTest.logView(user, docId))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(interactionRepository, never()).save(any());
    }

    // --- logBookmark ---

    @Test
    void given_noExistingBookmark_when_logBookmark_then_persistsInteractionWithKindBookmark() {
        User user = userWithId();
        Document doc = publicDoc(UUID.randomUUID(), "author@example.com");

        when(interactionRepository.existsByUserAndDocumentAndKind(user.getId(), doc.getId(), InteractionKind.BOOKMARK))
                .thenReturn(false);

        classUnderTest.logBookmark(user, doc);

        ArgumentCaptor<UserDocumentInteraction> captor = ArgumentCaptor.forClass(UserDocumentInteraction.class);
        verify(interactionRepository).save(captor.capture());
        UserDocumentInteraction toSave = captor.getValue();
        assertThat(toSave.getUser()).isSameAs(user);
        assertThat(toSave.getDocument()).isSameAs(doc);
        assertThat(toSave.getKind()).isEqualTo(InteractionKind.BOOKMARK);
    }

    @Test
    void given_existingBookmark_when_logBookmark_then_isNoOp() {
        User user = userWithId();
        Document doc = publicDoc(UUID.randomUUID(), "author@example.com");

        when(interactionRepository.existsByUserAndDocumentAndKind(user.getId(), doc.getId(), InteractionKind.BOOKMARK))
                .thenReturn(true);

        classUnderTest.logBookmark(user, doc);

        verify(interactionRepository, never()).save(any());
    }

    // --- helpers ---

    private static User userWithId() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        return user;
    }

    private static Document publicDoc(UUID id, String authorEmail) {
        User author = new User();
        author.setId(UUID.randomUUID());
        author.setEmail(authorEmail);
        Document doc = new Document();
        doc.setId(id);
        doc.setAuthor(author);
        doc.setVisibility(Visibility.PUBLIC);
        return doc;
    }

    private static Document privateDoc(UUID id, UUID authorUserId) {
        User author = new User();
        author.setId(authorUserId);
        author.setEmail("other@example.com");
        Document doc = new Document();
        doc.setId(id);
        doc.setAuthor(author);
        doc.setVisibility(Visibility.PRIVATE);
        return doc;
    }
}
