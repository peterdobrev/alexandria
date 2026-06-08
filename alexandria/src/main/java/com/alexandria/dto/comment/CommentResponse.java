package com.alexandria.dto.comment;

import com.alexandria.dto.document.AuthorSummary;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        AuthorSummary author,
        String body,
        Instant createdAt
) {}
