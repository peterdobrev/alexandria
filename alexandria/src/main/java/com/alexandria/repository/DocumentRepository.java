package com.alexandria.repository;

import com.alexandria.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.documentCategories dc LEFT JOIN FETCH dc.category WHERE d.id = :id")
    Optional<Document> findWithCategoriesById(@Param("id") UUID id);
}
