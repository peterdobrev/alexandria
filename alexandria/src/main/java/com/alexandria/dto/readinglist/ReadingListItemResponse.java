package com.alexandria.dto.readinglist;

import com.alexandria.dto.document.DocumentSummary;

import java.time.Instant;
import java.util.UUID;

public record ReadingListItemResponse(UUID id, DocumentSummary document, Instant addedAt) {}
