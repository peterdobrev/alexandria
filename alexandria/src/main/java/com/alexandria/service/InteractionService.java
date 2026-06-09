package com.alexandria.service;

import com.alexandria.entity.Document;
import com.alexandria.entity.InteractionKind;
import com.alexandria.entity.User;
import com.alexandria.entity.UserDocumentInteraction;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.InteractionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Transactional
@RequiredArgsConstructor
public class InteractionService {

    private static final Duration VIEW_DEDUP_WINDOW = Duration.ofMinutes(5);

    private final InteractionRepository interactionRepository;
    private final DocumentRepository documentRepository;

    public void logView(User currentUser, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        assertVisible(document, currentUser);

        Instant since = Instant.now().minus(VIEW_DEDUP_WINDOW);
        if (interactionRepository.existsRecent(currentUser.getId(), documentId, InteractionKind.VIEW, since)) {
            return;
        }

        UserDocumentInteraction interaction = new UserDocumentInteraction();
        interaction.setUser(currentUser);
        interaction.setDocument(document);
        interaction.setKind(InteractionKind.VIEW);
        interactionRepository.save(interaction);
    }

    /**
     * Server-only entry point invoked by {@code ReadingListService.addItem}.
     * Idempotent — a BOOKMARK row for {@code (user, document)} is never duplicated.
     * Removed reading-list items intentionally do NOT unlog the BOOKMARK; the signal
     * represents historical interest, which remains a valid recommendation cue.
     */
    public void logBookmark(User currentUser, Document document) {
        if (interactionRepository.existsByUserAndDocumentAndKind(
                currentUser.getId(), document.getId(), InteractionKind.BOOKMARK)) {
            return;
        }
        UserDocumentInteraction interaction = new UserDocumentInteraction();
        interaction.setUser(currentUser);
        interaction.setDocument(document);
        interaction.setKind(InteractionKind.BOOKMARK);
        interactionRepository.save(interaction);
    }

    private static void assertVisible(Document document, User currentUser) {
        if (document.getVisibility() == Visibility.PUBLIC) {
            return;
        }
        if (currentUser != null
                && document.getAuthor() != null
                && currentUser.getId().equals(document.getAuthor().getId())) {
            return;
        }
        throw new DocumentNotFoundException(document.getId());
    }
}
