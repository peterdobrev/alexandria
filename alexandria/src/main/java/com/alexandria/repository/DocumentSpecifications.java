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
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Document> hasCategory(UUID categoryId) {
        return (root, query, cb) -> {
            Join<Object, Object> documentCategories = root.join("documentCategories");
            Join<Object, Object> category = documentCategories.join("category");
            return cb.equal(category.get("id"), categoryId);
        };
    }

    public static Specification<Document> hasAuthor(UUID authorId) {
        return (root, query, cb) -> cb.equal(root.get("author").get("id"), authorId);
    }

    public static Specification<Document> titleContains(String search) {
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern);
    }

    public static Specification<Document> isPublic() {
        return (root, query, cb) -> cb.equal(root.get("visibility"), Visibility.PUBLIC);
    }

    public static Specification<Document> isVisibleToUser(UUID currentUserId) {
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("visibility"), Visibility.PUBLIC),
                cb.equal(root.get("author").get("id"), currentUserId)
        );
    }
}
