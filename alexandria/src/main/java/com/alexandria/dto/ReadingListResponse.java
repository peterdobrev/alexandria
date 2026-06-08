package com.alexandria.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReadingListResponse(UUID id, String name, Instant createdAt, List<ReadingListItemResponse> items) {}
