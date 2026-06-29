package com.alexandria.mapper;

import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.dto.readinglist.ReadingListItemResponse;
import com.alexandria.dto.readinglist.ReadingListResponse;
import com.alexandria.dto.readinglist.ReadingListSummaryResponse;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import com.alexandria.entity.Visibility;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RequiredArgsConstructor
public class ReadingListMapper {

    private final DocumentMapper documentMapper;

    public ReadingListSummaryResponse toSummaryResponse(ReadingList list) {
        return new ReadingListSummaryResponse(list.getId(), list.getName(), list.getCreatedAt());
    }

    public ReadingListResponse toResponse(ReadingList list, Map<UUID, DocumentSummary> summariesByDocumentId) {
        UUID ownerId = list.getUser().getId();
        List<ReadingListItemResponse> items = list.getItems().stream()
                .map(item -> pairWithSummary(item, summariesByDocumentId.get(item.getDocument().getId())))
                .filter(Objects::nonNull)
                .filter(response -> isVisibleTo(response.document(), ownerId))
                .toList();
        return new ReadingListResponse(list.getId(), list.getName(), list.getCreatedAt(), items);
    }

    public ReadingListItemResponse toItemResponse(ReadingListItem item) {
        return new ReadingListItemResponse(
                item.getId(),
                documentMapper.toSummary(item.getDocument()),
                item.getAddedAt()
        );
    }

    private static ReadingListItemResponse pairWithSummary(ReadingListItem item, DocumentSummary summary) {
        return summary == null ? null : new ReadingListItemResponse(item.getId(), summary, item.getAddedAt());
    }

    private static boolean isVisibleTo(DocumentSummary document, UUID viewerId) {
        if (document.visibility() == Visibility.PUBLIC) {
            return true;
        }
        return document.author() != null && document.author().id().equals(viewerId);
    }
}
