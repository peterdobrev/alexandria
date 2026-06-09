package com.alexandria.repository;

import java.util.List;
import java.util.UUID;

/**
 * Thin abstraction over the native recommendation queries. Mockable from
 * {@code RecommendationServiceTest} so unit tests don't need a database;
 * exercised against real Postgres in {@code RecommendationQueriesIT}.
 */
public interface RecommendationQueryRunner {

    List<UUID> runScoreQuery(UUID userId, int pageSize, int offset);

    long countScoreQuery(UUID userId);

    List<UUID> runColdStartQuery(UUID userId, int pageSize, int offset);

    long countColdStartQuery(UUID userId);
}
