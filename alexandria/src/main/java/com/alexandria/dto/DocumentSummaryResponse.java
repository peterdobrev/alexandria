package com.alexandria.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentSummaryResponse(
        UUID id,
        String title,
        String type,
        UserResponse author,
        List<CategoryResponse> categories,
        Instant createdAt
) {}
