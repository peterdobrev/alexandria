package com.alexandria.dto.document;

import com.alexandria.entity.Visibility;
import com.alexandria.validation.NullOrNotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record UpdateDocumentRequest(

        @NullOrNotBlank
        @Size(max = 255)
        String title,

        @Size(max = 5000)
        String description,

        Set<UUID> categoryIds,

        Visibility visibility
) {}
