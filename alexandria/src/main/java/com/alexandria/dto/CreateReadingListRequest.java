package com.alexandria.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateReadingListRequest(@NotBlank String name) {}
