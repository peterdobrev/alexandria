package com.alexandria.dto.user;

import com.alexandria.validation.NullOrNotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(

        @NullOrNotBlank(message = "Display name must not be blank")
        @Size(max = 255, message = "Display name must be at most 255 characters")
        String displayName,

        @NullOrNotBlank(message = "Password must not be blank")
        @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
        String password
) {}
