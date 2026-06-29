package com.alexandria.dto.readinglist;

import java.time.Instant;
import java.util.UUID;

public record ReadingListSummaryResponse(UUID id, String name, Instant createdAt) {}
