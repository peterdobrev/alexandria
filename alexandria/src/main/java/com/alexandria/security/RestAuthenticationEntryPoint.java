package com.alexandria.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Returns a JSON body — same shape as {@link com.alexandria.dto.ErrorResponse} —
 * when an unauthenticated request hits a protected endpoint, instead of Spring
 * Security's default empty 401.
 *
 * <p>Wired into the security filter chain by {@link SecurityConfig}. Triggered
 * by {@code ExceptionTranslationFilter} whenever the SecurityContext has no
 * authentication, including the case where {@link JwtAuthenticationFilter}
 * caught an invalid/expired token and let the request continue unauthenticated.
 *
 * <p>The response is hand-serialized to avoid pulling in a JSON library beyond
 * what's already on the classpath. The static message and timestamp are the only
 * dynamic fields — both are safe to interpolate without escaping.
 */
@Slf4j
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String MESSAGE = "Authentication required";

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("Unauthenticated request to {}: {}", request.getRequestURI(), authException.getMessage());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String path = escape(request.getRequestURI());
        String body = """
                {"status":%d,"error":"%s","message":"%s","timestamp":"%s","path":"%s"}"""
                .formatted(
                        HttpStatus.UNAUTHORIZED.value(),
                        HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                        MESSAGE,
                        Instant.now(),
                        path
                );

        try (PrintWriter writer = response.getWriter()) {
            writer.write(body);
        }
    }

    /** Minimal JSON-string escaper for the request path. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"', '\\' -> sb.append('\\').append(c);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
