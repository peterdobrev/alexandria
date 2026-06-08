package com.alexandria.dto.document;

import com.alexandria.entity.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreateArticleRequest(

        @NotBlank
        @Size(max = 255)
        String title,

        @Size(max = 5000)
        String description,

        @NotBlank
        String type,

        @NotBlank
        String body,

        Set<UUID> categoryIds,

        Visibility visibility
) {}
