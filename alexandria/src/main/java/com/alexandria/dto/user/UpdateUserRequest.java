package com.alexandria.dto.user;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(

        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName,

        @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
        String password
) {}
