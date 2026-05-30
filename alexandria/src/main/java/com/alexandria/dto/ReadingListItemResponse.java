package com.alexandria.dto;

import java.time.Instant;
import java.util.UUID;

public record ReadingListItemResponse(UUID id, DocumentSummaryResponse document, Instant addedAt) {}
