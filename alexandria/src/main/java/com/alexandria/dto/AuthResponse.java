package com.alexandria.dto;

import java.util.UUID;

public record AuthResponse(UUID userId, String token, String tokenType, long expiresIn) {

    public static AuthResponse of(UUID userId, String token, long expirationMillis) {
        return new AuthResponse(userId, token, "Bearer", expirationMillis / 1000);
    }

    @Override
    public String toString() {
        return "AuthResponse[userId=" + userId
                + ", token=***"
                + ", tokenType=" + tokenType
                + ", expiresIn=" + expiresIn + "]";
    }
}
