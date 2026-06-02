package com.alexandria.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

import java.util.List;
import java.util.UUID;

public record CreateDocumentRequest(
        @NotBlank String title,
        String description,
        @NotBlank String type,
        @NotBlank @URL String fileUrl,
        List<UUID> categoryIds
) {}
