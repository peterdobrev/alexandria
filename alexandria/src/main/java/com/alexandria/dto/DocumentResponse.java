package com.alexandria.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String title,
        String description,
        String type,
        String fileUrl,
        UserResponse author,
        List<CategoryResponse> categories,
        Instant createdAt,
        Instant updatedAt
) {}
