package com.alexandria.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateDocumentRequest(
        @Size(min = 1) String title,
        String description,
        List<UUID> categoryIds
) {}
