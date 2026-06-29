package com.alexandria.repository;

import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.CategorySummary;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.Document;
import com.alexandria.entity.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class DocumentRepositoryImpl implements DocumentRepositoryCustom {

    private final EntityManager entityManager;

    public DocumentRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<DocumentSummary> findSummaryPage(Specification<Document> specification, Pageable pageable) {
        long total = count(specification);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<DocumentRow> rows = fetchPage(specification, pageable);
        List<UUID> documentIds = rows.stream().map(DocumentRow::id).toList();
        Map<UUID, Set<CategorySummary>> categoriesByDocument = loadCategories(documentIds);

        List<DocumentSummary> content = rows.stream()
                .map(row -> row.toSummary(categoriesByDocument.getOrDefault(row.id(), Set.of())))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    private long count(Specification<Document> specification) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Document> root = query.from(Document.class);
        query.select(cb.countDistinct(root));
        Predicate predicate = specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        return entityManager.createQuery(query).getSingleResult();
    }

    private List<DocumentRow> fetchPage(Specification<Document> specification, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Document> root = query.from(Document.class);
        Join<Object, Object> author = root.join("author", JoinType.LEFT);

        Predicate predicate = specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        query.distinct(true);
        selectSummaryProjection(cb, query, root, author);
        query.orderBy(toOrders(pageable.getSort(), root, cb));

        return entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList()
                .stream()
                .map(DocumentRow::from)
                .toList();
    }

    @Override
    public List<DocumentSummary> findSummariesByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Document> root = query.from(Document.class);
        Join<Object, Object> author = root.join("author", JoinType.LEFT);
        query.where(root.get("id").in(ids));
        selectSummaryProjection(cb, query, root, author);

        List<DocumentRow> rows = entityManager.createQuery(query)
                .getResultList()
                .stream()
                .map(DocumentRow::from)
                .toList();

        Map<UUID, Set<CategorySummary>> categoriesByDocument =
                loadCategories(rows.stream().map(DocumentRow::id).toList());
        Map<UUID, DocumentSummary> summariesById = rows.stream()
                .collect(Collectors.toMap(
                        DocumentRow::id,
                        row -> row.toSummary(categoriesByDocument.getOrDefault(row.id(), Set.of()))));

        // Preserve the caller's ordering (recommendations rely on score order).
        return ids.stream()
                .map(summariesById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static void selectSummaryProjection(CriteriaBuilder cb, CriteriaQuery<Tuple> query,
                                                Root<Document> root, Join<Object, Object> author) {
        query.select(cb.tuple(
                root.get("id").alias("id"),
                root.get("title").alias("title"),
                root.get("description").alias("description"),
                root.get("type").alias("type"),
                root.get("visibility").alias("visibility"),
                author.get("id").alias("authorId"),
                author.get("displayName").alias("authorName"),
                presence(cb, root.get("uploadedFilePath"), "hasFile"),
                presence(cb, root.get("body"), "hasBody"),
                root.get("createdAt").alias("createdAt"),
                root.get("updatedAt").alias("updatedAt")
        ));
    }

    private Map<UUID, Set<CategorySummary>> loadCategories(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        List<Tuple> rows = entityManager.createQuery("""
                SELECT dc.document.id AS documentId, c.id AS categoryId, c.name AS categoryName
                FROM DocumentCategory dc
                JOIN dc.category c
                WHERE dc.document.id IN :documentIds
                """, Tuple.class)
                .setParameter("documentIds", documentIds)
                .getResultList();

        Map<UUID, Set<CategorySummary>> categoriesByDocument = new LinkedHashMap<>();
        for (Tuple row : rows) {
            UUID documentId = row.get("documentId", UUID.class);
            CategorySummary category = new CategorySummary(
                    row.get("categoryId", UUID.class),
                    row.get("categoryName", String.class));
            categoriesByDocument.computeIfAbsent(documentId, _ -> new LinkedHashSet<>()).add(category);
        }
        return categoriesByDocument;
    }

    private static Selection<Boolean> presence(CriteriaBuilder cb, Path<?> path, String alias) {
        return cb.<Boolean>selectCase()
                .when(cb.isNotNull(path), true)
                .otherwise(false)
                .alias(alias);
    }

    private static List<Order> toOrders(Sort sort, Root<Document> root, CriteriaBuilder cb) {
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Path<Object> path = root.get(order.getProperty());
            orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
        }
        return orders;
    }

    private record DocumentRow(
            UUID id,
            String title,
            String description,
            String type,
            Visibility visibility,
            UUID authorId,
            String authorName,
            boolean hasFile,
            boolean hasBody,
            Instant createdAt,
            Instant updatedAt
    ) {
        static DocumentRow from(Tuple tuple) {
            return new DocumentRow(
                    tuple.get("id", UUID.class),
                    tuple.get("title", String.class),
                    tuple.get("description", String.class),
                    tuple.get("type", String.class),
                    tuple.get("visibility", Visibility.class),
                    tuple.get("authorId", UUID.class),
                    tuple.get("authorName", String.class),
                    Boolean.TRUE.equals(tuple.get("hasFile", Boolean.class)),
                    Boolean.TRUE.equals(tuple.get("hasBody", Boolean.class)),
                    tuple.get("createdAt", Instant.class),
                    tuple.get("updatedAt", Instant.class)
            );
        }

        DocumentSummary toSummary(Set<CategorySummary> categories) {
            AuthorSummary author = authorId == null ? null : new AuthorSummary(authorId, authorName);
            return new DocumentSummary(
                    id, title, description, type, visibility, author,
                    categories, hasFile, hasBody, createdAt, updatedAt);
        }
    }
}
