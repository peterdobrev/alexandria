package com.alexandria.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "document_categories")
public class DocumentCategory {

    @EmbeddedId
    private DocumentCategoryId id;

    @ManyToOne
    @MapsId("documentId")
    @JoinColumn(name = "document_id")
    private Document document;

    @ManyToOne
    @MapsId("categoryId")
    @JoinColumn(name = "category_id")
    private Category category;
}
