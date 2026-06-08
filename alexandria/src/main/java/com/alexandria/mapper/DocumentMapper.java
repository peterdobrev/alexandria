package com.alexandria.mapper;

import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.CategorySummary;
import com.alexandria.dto.document.DocumentDetail;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.Document;
import com.alexandria.entity.DocumentCategory;
import com.alexandria.entity.User;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DocumentMapper {

    public DocumentSummary toSummary(Document d) {
        return new DocumentSummary(
                d.getId(),
                d.getTitle(),
                d.getDescription(),
                d.getType(),
                d.getVisibility(),
                toAuthorSummary(d.getAuthor()),
                toCategorySummaries(d.getDocumentCategories()),
                d.getUploadedFilePath() != null,
                d.getBody() != null,
                d.getSizeBytes(),
                d.getContentType(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }

    public DocumentDetail toDetail(Document d) {
        return new DocumentDetail(
                d.getId(),
                d.getTitle(),
                d.getDescription(),
                d.getType(),
                d.getVisibility(),
                toAuthorSummary(d.getAuthor()),
                toCategorySummaries(d.getDocumentCategories()),
                d.getUploadedFilePath() != null,
                d.getBody() != null,
                d.getSizeBytes(),
                d.getContentType(),
                d.getBody(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }

    private AuthorSummary toAuthorSummary(User author) {
        if (author == null) {
            return null;
        }
        return new AuthorSummary(author.getId(), author.getDisplayName());
    }

    private Set<CategorySummary> toCategorySummaries(List<DocumentCategory> documentCategories) {
        if (documentCategories == null || documentCategories.isEmpty()) {
            return Collections.emptySet();
        }
        return documentCategories.stream()
                .map(DocumentCategory::getCategory)
                .filter(c -> c != null)
                .map(c -> new CategorySummary(c.getId(), c.getName()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
