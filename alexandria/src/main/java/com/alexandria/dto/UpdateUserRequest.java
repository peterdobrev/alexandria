package com.alexandria.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(min = 1, max = 100) String displayName,
        @Size(min = 8) String password
) {}
