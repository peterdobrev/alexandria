package com.alexandria.dto.auth;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

public record AuthResponse(UUID userId, String token, String tokenType, long expiresIn) {

    public static AuthResponse of(UUID userId, String token, long expirationMillis) {
        return new AuthResponse(userId, token, "Bearer", expirationMillis / 1000);
    }

    @Override
    public @NonNull String toString() {
        return "AuthResponse[userId=" + userId
                + ", token=***"
                + ", tokenType=" + tokenType
                + ", expiresIn=" + expiresIn + "]";
    }
}
