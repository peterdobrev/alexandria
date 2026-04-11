package com.alexandria.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "reading_list_items")
public class ReadingListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "list_id")
    private ReadingList readingList;

    @ManyToOne
    @JoinColumn(name = "document_id")
    private Document document;

    @Column(name = "added_at")
    private LocalDateTime addedAt;
}
