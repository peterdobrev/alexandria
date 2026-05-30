package com.alexandria.repository;

import com.alexandria.entity.ReadingListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReadingListItemRepository extends JpaRepository<ReadingListItem, UUID> {

    Optional<ReadingListItem> findByReadingListIdAndDocumentId(UUID readingListId, UUID documentId);
}
