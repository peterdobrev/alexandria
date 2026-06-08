package com.alexandria.dto.document;

import com.alexandria.entity.Visibility;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DocumentDetail(
        UUID id,
        String title,
        String description,
        String type,
        Visibility visibility,
        AuthorSummary author,
        Set<CategorySummary> categories,
        boolean hasFile,
        boolean hasBody,
        Long sizeBytes,
        String contentType,
        String body,
        Instant createdAt,
        Instant updatedAt
) {}
