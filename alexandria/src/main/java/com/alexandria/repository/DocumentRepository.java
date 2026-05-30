package com.alexandria.repository;

import com.alexandria.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Query(value = """
            SELECT d FROM Document d
            WHERE (:type IS NULL OR d.type = :type)
            AND (:authorId IS NULL OR d.author.id = :authorId)
            AND (:search IS NULL OR LOWER(d.title) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:categoryId IS NULL OR EXISTS (
                SELECT dc FROM DocumentCategory dc WHERE dc.document = d AND dc.category.id = :categoryId
            ))
            """,
            countQuery = """
            SELECT COUNT(d) FROM Document d
            WHERE (:type IS NULL OR d.type = :type)
            AND (:authorId IS NULL OR d.author.id = :authorId)
            AND (:search IS NULL OR LOWER(d.title) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:categoryId IS NULL OR EXISTS (
                SELECT dc FROM DocumentCategory dc WHERE dc.document = d AND dc.category.id = :categoryId
            ))
            """)
    Page<Document> findWithFilters(
            @Param("type") String type,
            @Param("authorId") UUID authorId,
            @Param("categoryId") UUID categoryId,
            @Param("search") String search,
            Pageable pageable
    );
}
