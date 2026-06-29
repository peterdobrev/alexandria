package com.alexandria.dto.readinglist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReadingListRequest(@NotBlank @Size(max = 255) String name) {}
