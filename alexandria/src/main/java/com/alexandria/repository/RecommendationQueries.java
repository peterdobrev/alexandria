package com.alexandria.repository;

/**
 * Native SQL constants used by the recommendation engine. Held as constants
 * (not in {@code @Query} annotations) for readability — the queries are large.
 *
 * <p>Both queries return a single column {@code id} of type {@code uuid}.
 */
public final class RecommendationQueries {

    private RecommendationQueries() {}

    /**
     * Item-item collaborative filtering score for {@code :userId}.
     * Excludes documents the user has already interacted with, the user's own
     * documents, and PRIVATE documents. Ordered by score DESC, created_at DESC.
     */
    public static final String SCORE_QUERY = """
        WITH user_history AS (
            SELECT document_id,
                   3 * MAX(CASE WHEN kind = 'BOOKMARK' THEN 1 ELSE 0 END)
                 + LEAST(SUM(CASE WHEN kind = 'VIEW' THEN 1 ELSE 0 END), 5) AS weight
            FROM user_document_interactions
            WHERE user_id = :userId
            GROUP BY document_id
        ),
        doc_weights AS (
            SELECT user_id, document_id,
                   3 * MAX(CASE WHEN kind = 'BOOKMARK' THEN 1 ELSE 0 END)
                 + LEAST(SUM(CASE WHEN kind = 'VIEW' THEN 1 ELSE 0 END), 5) AS w
            FROM user_document_interactions
            GROUP BY user_id, document_id
        ),
        doc_norms AS (
            SELECT document_id, sqrt(SUM(w * w)) AS norm
            FROM doc_weights
            GROUP BY document_id
        ),
        candidates AS (
            SELECT d.id, d.created_at
            FROM documents d
            WHERE d.visibility = 'PUBLIC'
              AND d.author_id != :userId
              AND d.id NOT IN (SELECT document_id FROM user_document_interactions WHERE user_id = :userId)
        ),
        scored AS (
            SELECT
                c.id,
                c.created_at,
                SUM(
                    (cand.w * hist.w)
                    / NULLIF(cand_norm.norm * hist_norm.norm, 0)
                ) * MAX(uh.weight) AS score
            FROM candidates c
            JOIN doc_weights cand     ON cand.document_id = c.id
            JOIN doc_weights hist     ON hist.user_id = cand.user_id AND hist.document_id != c.id
            JOIN user_history uh      ON uh.document_id = hist.document_id
            JOIN doc_norms cand_norm  ON cand_norm.document_id = c.id
            JOIN doc_norms hist_norm  ON hist_norm.document_id = hist.document_id
            GROUP BY c.id, c.created_at
        )
        SELECT id
        FROM scored
        WHERE score > 0
        ORDER BY score DESC, created_at DESC
        LIMIT :pageSize OFFSET :offset
        """;

    /**
     * Cold-start: popular PUBLIC documents in categories the user has engaged with
     * (authored, commented, or in a reading list). Falls through to globally popular
     * when the user has zero engagement of any kind.
     */
    public static final String COLD_START_QUERY = """
        WITH affinity_categories AS (
            SELECT DISTINCT dc.category_id
            FROM document_categories dc
            WHERE dc.document_id IN (
                SELECT id FROM documents WHERE author_id = :userId
                UNION
                SELECT document_id FROM comments WHERE author_id = :userId
                UNION
                SELECT rli.document_id FROM reading_list_items rli
                    JOIN reading_lists rl ON rl.id = rli.list_id
                    WHERE rl.user_id = :userId
            )
        ),
        candidates AS (
            SELECT d.id, d.created_at,
                   (SELECT COUNT(*) FROM user_document_interactions udi WHERE udi.document_id = d.id) AS pop
            FROM documents d
            WHERE d.visibility = 'PUBLIC'
              AND d.author_id != :userId
              AND (
                  EXISTS (
                      SELECT 1 FROM document_categories dc
                      WHERE dc.document_id = d.id
                        AND dc.category_id IN (SELECT category_id FROM affinity_categories)
                  )
                  OR NOT EXISTS (SELECT 1 FROM affinity_categories)
              )
        )
        SELECT id FROM candidates
        ORDER BY pop DESC, created_at DESC
        LIMIT :pageSize OFFSET :offset
        """;

    /** Total count companion to {@link #SCORE_QUERY} (no LIMIT/OFFSET). */
    public static final String SCORE_COUNT_QUERY = """
        WITH user_history AS (
            SELECT document_id,
                   3 * MAX(CASE WHEN kind = 'BOOKMARK' THEN 1 ELSE 0 END)
                 + LEAST(SUM(CASE WHEN kind = 'VIEW' THEN 1 ELSE 0 END), 5) AS weight
            FROM user_document_interactions
            WHERE user_id = :userId
            GROUP BY document_id
        ),
        doc_weights AS (
            SELECT user_id, document_id,
                   3 * MAX(CASE WHEN kind = 'BOOKMARK' THEN 1 ELSE 0 END)
                 + LEAST(SUM(CASE WHEN kind = 'VIEW' THEN 1 ELSE 0 END), 5) AS w
            FROM user_document_interactions
            GROUP BY user_id, document_id
        ),
        doc_norms AS (
            SELECT document_id, sqrt(SUM(w * w)) AS norm
            FROM doc_weights
            GROUP BY document_id
        ),
        candidates AS (
            SELECT d.id
            FROM documents d
            WHERE d.visibility = 'PUBLIC'
              AND d.author_id != :userId
              AND d.id NOT IN (SELECT document_id FROM user_document_interactions WHERE user_id = :userId)
        ),
        scored AS (
            SELECT
                c.id,
                SUM(
                    (cand.w * hist.w)
                    / NULLIF(cand_norm.norm * hist_norm.norm, 0)
                ) * MAX(uh.weight) AS score
            FROM candidates c
            JOIN doc_weights cand     ON cand.document_id = c.id
            JOIN doc_weights hist     ON hist.user_id = cand.user_id AND hist.document_id != c.id
            JOIN user_history uh      ON uh.document_id = hist.document_id
            JOIN doc_norms cand_norm  ON cand_norm.document_id = c.id
            JOIN doc_norms hist_norm  ON hist_norm.document_id = hist.document_id
            GROUP BY c.id
        )
        SELECT COUNT(*) FROM scored WHERE score > 0
        """;

    /** Total count companion to {@link #COLD_START_QUERY}. */
    public static final String COLD_START_COUNT_QUERY = """
        WITH affinity_categories AS (
            SELECT DISTINCT dc.category_id
            FROM document_categories dc
            WHERE dc.document_id IN (
                SELECT id FROM documents WHERE author_id = :userId
                UNION
                SELECT document_id FROM comments WHERE author_id = :userId
                UNION
                SELECT rli.document_id FROM reading_list_items rli
                    JOIN reading_lists rl ON rl.id = rli.list_id
                    WHERE rl.user_id = :userId
            )
        )
        SELECT COUNT(*)
        FROM documents d
        WHERE d.visibility = 'PUBLIC'
          AND d.author_id != :userId
          AND (
              EXISTS (
                  SELECT 1 FROM document_categories dc
                  WHERE dc.document_id = d.id
                    AND dc.category_id IN (SELECT category_id FROM affinity_categories)
              )
              OR NOT EXISTS (SELECT 1 FROM affinity_categories)
          )
        """;
}
