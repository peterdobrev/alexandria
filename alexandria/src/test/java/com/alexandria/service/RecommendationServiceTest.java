package com.alexandria.service;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.Visibility;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.InteractionRepository;
import com.alexandria.repository.RecommendationQueryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private InteractionRepository interactionRepository;
    @Mock private RecommendationQueryRunner queryRunner;
    @Mock private DocumentRepository documentRepository;

    private RecommendationService classUnderTest;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        classUnderTest = new RecommendationService(
                interactionRepository, queryRunner, documentRepository);
    }

    @Test
    void given_userWithNoInteractions_when_getRecommendations_then_runsColdStartQuery() {
        UUID docId = UUID.randomUUID();
        DocumentSummary summary = summaryWithId(docId);

        when(interactionRepository.countByUserId(userId)).thenReturn(0L);
        when(queryRunner.runColdStartQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of(docId));
        when(queryRunner.countColdStartQuery(userId)).thenReturn(1L);
        when(documentRepository.findSummariesByIds(List.of(docId))).thenReturn(List.of(summary));

        PageResponse<DocumentSummary> result = classUnderTest.getRecommendations(userId, PageRequest.of(0, 20));

        assertThat(result.content()).containsExactly(summary);
        verify(queryRunner, never()).runScoreQuery(eq(userId), anyInt(), anyInt());
    }

    @Test
    void given_userWithInteractionsAndCandidates_when_getRecommendations_then_runsScoringQuery() {
        UUID docId = UUID.randomUUID();
        DocumentSummary summary = summaryWithId(docId);

        when(interactionRepository.countByUserId(userId)).thenReturn(7L);
        when(queryRunner.runScoreQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of(docId));
        when(queryRunner.countScoreQuery(userId)).thenReturn(1L);
        when(documentRepository.findSummariesByIds(List.of(docId))).thenReturn(List.of(summary));

        PageResponse<DocumentSummary> result = classUnderTest.getRecommendations(userId, PageRequest.of(0, 20));

        assertThat(result.content()).containsExactly(summary);
        verify(queryRunner, never()).runColdStartQuery(eq(userId), anyInt(), anyInt());
    }

    @Test
    void given_scoringReturnsEmpty_when_getRecommendations_then_fallsBackToColdStart() {
        UUID docId = UUID.randomUUID();
        DocumentSummary summary = summaryWithId(docId);

        when(interactionRepository.countByUserId(userId)).thenReturn(2L);
        when(queryRunner.runScoreQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of());
        when(queryRunner.runColdStartQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of(docId));
        when(queryRunner.countColdStartQuery(userId)).thenReturn(1L);
        when(documentRepository.findSummariesByIds(List.of(docId))).thenReturn(List.of(summary));

        PageResponse<DocumentSummary> result = classUnderTest.getRecommendations(userId, PageRequest.of(0, 20));

        assertThat(result.content()).containsExactly(summary);
    }

    @Test
    void given_emptyDatabase_when_getRecommendations_then_returnsEmptyPage() {
        when(interactionRepository.countByUserId(userId)).thenReturn(0L);
        when(queryRunner.runColdStartQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of());
        when(queryRunner.countColdStartQuery(userId)).thenReturn(0L);

        PageResponse<DocumentSummary> result = classUnderTest.getRecommendations(userId, PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verify(documentRepository, never()).findSummariesByIds(anyList());
    }

    @Test
    void given_scoredIds_when_getRecommendations_then_preservesOrderFromQuery() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DocumentSummary summaryFirst = summaryWithId(first);
        DocumentSummary summarySecond = summaryWithId(second);

        when(interactionRepository.countByUserId(userId)).thenReturn(5L);
        when(queryRunner.runScoreQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of(first, second));
        when(queryRunner.countScoreQuery(userId)).thenReturn(2L);
        when(documentRepository.findSummariesByIds(List.of(first, second)))
                .thenReturn(List.of(summaryFirst, summarySecond));

        PageResponse<DocumentSummary> result = classUnderTest.getRecommendations(userId, PageRequest.of(0, 20));

        assertThat(result.content()).containsExactly(summaryFirst, summarySecond);
    }

    @Test
    void given_documentDeletedConcurrentlyDuringPagination_when_getRecommendations_then_lastFlagComesFromTotalPages() {
        // total=40 across 2 pages of 20; on page 0 a doc was deleted between the score
        // query and the summary fetch, so content.size()==1 < pageSize. last must be
        // false because we are still on page 0 of 2.
        UUID present = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        DocumentSummary summary = summaryWithId(present);

        when(interactionRepository.countByUserId(userId)).thenReturn(5L);
        when(queryRunner.runScoreQuery(eq(userId), eq(20), eq(0))).thenReturn(List.of(present, stale));
        when(queryRunner.countScoreQuery(userId)).thenReturn(40L);
        when(documentRepository.findSummariesByIds(List.of(present, stale))).thenReturn(List.of(summary));

        PageResponse<DocumentSummary> result = classUnderTest.getRecommendations(userId, PageRequest.of(0, 20));

        assertThat(result.last()).isFalse();
        assertThat(result.totalPages()).isEqualTo(2);
    }

    // --- helpers ---

    private static DocumentSummary summaryWithId(UUID id) {
        return new DocumentSummary(
                id, "title", "desc", "ARTICLE", Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.of(),
                false, true,
                Instant.now(), Instant.now()
        );
    }
}
