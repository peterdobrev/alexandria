package com.alexandria.repository;

import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

public interface DocumentRepositoryCustom {

    /**
     * Returns a page of {@link DocumentSummary} without loading the (potentially large)
     * document {@code body}: the {@code hasBody}/{@code hasFile} flags are computed in SQL,
     * and each page's categories are fetched in a single batched query to avoid N+1 selects.
     */
    Page<DocumentSummary> findSummaryPage(Specification<Document> specification, Pageable pageable);

    /**
     * Returns {@link DocumentSummary} for the given ids, in the same order as the input,
     * using the same body-free projection and batched category load as
     * {@link #findSummaryPage}. Ids with no matching document are skipped.
     */
    List<DocumentSummary> findSummariesByIds(List<UUID> ids);
}
