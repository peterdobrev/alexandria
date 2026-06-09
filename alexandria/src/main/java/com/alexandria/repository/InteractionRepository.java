package com.alexandria.repository;

import com.alexandria.entity.InteractionKind;
import com.alexandria.entity.UserDocumentInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface InteractionRepository extends JpaRepository<UserDocumentInteraction, UUID> {

    long countByUserId(UUID userId);

    @Query("""
        SELECT COUNT(i) > 0
        FROM UserDocumentInteraction i
        WHERE i.user.id = :userId
          AND i.document.id = :documentId
          AND i.kind = :kind
          AND i.interactedAt > :since
        """)
    boolean existsRecent(@Param("userId") UUID userId,
                         @Param("documentId") UUID documentId,
                         @Param("kind") InteractionKind kind,
                         @Param("since") Instant since);

    @Query("""
        SELECT COUNT(i) > 0
        FROM UserDocumentInteraction i
        WHERE i.user.id = :userId
          AND i.document.id = :documentId
          AND i.kind = :kind
        """)
    boolean existsByUserAndDocumentAndKind(@Param("userId") UUID userId,
                                           @Param("documentId") UUID documentId,
                                           @Param("kind") InteractionKind kind);
}
