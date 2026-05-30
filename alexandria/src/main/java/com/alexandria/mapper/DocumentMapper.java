package com.alexandria.mapper;

import com.alexandria.dto.CategoryResponse;
import com.alexandria.dto.DocumentResponse;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.entity.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentMapper {

    private final UserMapper userMapper;

    public DocumentSummaryResponse toSummaryResponse(Document document) {
        return new DocumentSummaryResponse(
                document.getId(),
                document.getTitle(),
                document.getType(),
                userMapper.toResponse(document.getAuthor()),
                toCategories(document),
                document.getCreatedAt()
        );
    }

    public DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getDescription(),
                document.getType(),
                document.getFileUrl(),
                userMapper.toResponse(document.getAuthor()),
                toCategories(document),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    private List<CategoryResponse> toCategories(Document document) {
        return document.getDocumentCategories().stream()
                .map(dc -> new CategoryResponse(dc.getCategory().getId(), dc.getCategory().getName()))
                .toList();
    }
}
