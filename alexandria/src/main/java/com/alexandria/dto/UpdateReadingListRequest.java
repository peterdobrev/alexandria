package com.alexandria.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateReadingListRequest(@NotBlank String name) {}
