package com.alexandria.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddReadingListItemRequest(@NotNull UUID documentId) {}
