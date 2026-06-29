package com.alexandria.mapper;

import com.alexandria.dto.readinglist.ReadingListItemResponse;
import com.alexandria.dto.readinglist.ReadingListResponse;
import com.alexandria.dto.readinglist.ReadingListSummaryResponse;
import com.alexandria.entity.Document;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import com.alexandria.entity.Visibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReadingListMapper {

    private final DocumentMapper documentMapper;

    public ReadingListSummaryResponse toSummaryResponse(ReadingList list) {
        return new ReadingListSummaryResponse(list.getId(), list.getName(), list.getCreatedAt());
    }

    public ReadingListResponse toResponse(ReadingList list) {
        UUID ownerId = list.getUser().getId();
        List<ReadingListItemResponse> items = list.getItems().stream()
                .filter(item -> isVisibleTo(item.getDocument(), ownerId))
                .map(this::toItemResponse)
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

    private static boolean isVisibleTo(Document document, UUID viewerId) {
        if (document.getVisibility() == Visibility.PUBLIC) {
            return true;
        }
        return document.getAuthor().getId().equals(viewerId);
    }
}
