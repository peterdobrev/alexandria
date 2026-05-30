package com.alexandria.dto;

import java.time.Instant;
import java.util.UUID;

public record ReadingListSummaryResponse(UUID id, String name, Instant createdAt) {}
