package com.alexandria.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record CreateDocumentRequest(
        @NotBlank String title,
        String description,
        @NotBlank String type,
        @NotBlank String fileUrl,
        List<UUID> categoryIds
) {}
