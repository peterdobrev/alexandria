package com.alexandria.mapper;

import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ReadingListMapper {

    private final DocumentMapper documentMapper;

    public ReadingListSummaryResponse toSummaryResponse(ReadingList list) {
        return new ReadingListSummaryResponse(list.getId(), list.getName(), list.getCreatedAt());
    }

    public ReadingListResponse toResponse(ReadingList list) {
        List<ReadingListItemResponse> items = list.getItems().stream()
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
}
