package com.alexandria.service;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.Document;
import com.alexandria.mapper.DocumentMapper;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.InteractionRepository;
import com.alexandria.repository.RecommendationQueryRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RecommendationService {

    private final InteractionRepository interactionRepository;
    private final RecommendationQueryRunner queryRunner;
    private final DocumentRepository documentRepository;
    private final DocumentMapper documentMapper;

    public PageResponse<DocumentSummary> getRecommendations(UUID userId, Pageable pageable) {
        int pageSize = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        int pageNumber = pageable.getPageNumber();

        long interactionCount = interactionRepository.countByUserId(userId);

        List<UUID> ids;
        long total;

        if (interactionCount == 0) {
            ids = queryRunner.runColdStartQuery(userId, pageSize, offset);
            total = queryRunner.countColdStartQuery(userId);
        } else {
            ids = queryRunner.runScoreQuery(userId, pageSize, offset);
            if (ids.isEmpty()) {
                ids = queryRunner.runColdStartQuery(userId, pageSize, offset);
                total = queryRunner.countColdStartQuery(userId);
            } else {
                total = queryRunner.countScoreQuery(userId);
            }
        }

        List<DocumentSummary> content = ids.isEmpty()
                ? List.of()
                : loadAndOrder(ids);

        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        boolean last = totalPages == 0 || pageNumber + 1 >= totalPages;

        return new PageResponse<>(content, pageNumber, pageSize, total, totalPages, last);
    }

    private List<DocumentSummary> loadAndOrder(List<UUID> orderedIds) {
        Map<UUID, Document> byId = documentRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        return orderedIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(documentMapper::toSummary)
                .toList();
    }
}
