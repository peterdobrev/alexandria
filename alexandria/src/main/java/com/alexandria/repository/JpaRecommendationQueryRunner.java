package com.alexandria.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JpaRecommendationQueryRunner implements RecommendationQueryRunner {

    private final EntityManager entityManager;

    @Override
    public List<UUID> runScoreQuery(UUID userId, int pageSize, int offset) {
        Query query = entityManager.createNativeQuery(RecommendationQueries.SCORE_QUERY);
        query.setParameter("userId", userId);
        query.setParameter("pageSize", pageSize);
        query.setParameter("offset", offset);
        return castToUuidList(query.getResultList());
    }

    @Override
    public long countScoreQuery(UUID userId) {
        Query query = entityManager.createNativeQuery(RecommendationQueries.SCORE_COUNT_QUERY);
        query.setParameter("userId", userId);
        return ((Number) query.getSingleResult()).longValue();
    }

    @Override
    public List<UUID> runColdStartQuery(UUID userId, int pageSize, int offset) {
        Query query = entityManager.createNativeQuery(RecommendationQueries.COLD_START_QUERY);
        query.setParameter("userId", userId);
        query.setParameter("pageSize", pageSize);
        query.setParameter("offset", offset);
        return castToUuidList(query.getResultList());
    }

    @Override
    public long countColdStartQuery(UUID userId) {
        Query query = entityManager.createNativeQuery(RecommendationQueries.COLD_START_COUNT_QUERY);
        query.setParameter("userId", userId);
        return ((Number) query.getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> castToUuidList(List<?> raw) {
        return (List<UUID>) raw;
    }
}
