package com.alexandria.repository;

import com.alexandria.entity.Document;
import com.alexandria.entity.Visibility;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public final class DocumentSpecifications {

    private DocumentSpecifications() {
    }

    public static Specification<Document> hasType(String type) {
        if (type == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Document> hasCategory(UUID categoryId) {
        if (categoryId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            Join<Object, Object> documentCategories = root.join("documentCategories");
            Join<Object, Object> category = documentCategories.join("category");
            return cb.equal(category.get("id"), categoryId);
        };
    }

    public static Specification<Document> hasAuthor(UUID authorId) {
        if (authorId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("author").get("id"), authorId);
    }

    public static Specification<Document> titleContains(String search) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
    }

    public static Specification<Document> isVisible(UUID currentUserId) {
        if (currentUserId == null) {
            return (root, query, cb) -> cb.equal(root.get("visibility"), Visibility.PUBLIC);
        }
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("visibility"), Visibility.PUBLIC),
                cb.equal(root.get("author").get("id"), currentUserId)
        );
    }
}
