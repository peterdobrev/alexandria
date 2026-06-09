package com.alexandria.controller;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.GlobalExceptionHandler;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.InteractionService;
import com.alexandria.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock private RecommendationService recommendationService;
    @Mock private InteractionService interactionService;
    @Mock private SecurityUtils securityUtils;

    @InjectMocks private RecommendationController classUnderTest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(classUnderTest)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void given_authenticatedUser_when_getRecommendations_then_returns200WithPageResponse() throws Exception {
        User currentUser = userWithId();
        DocumentSummary summary = summaryWithId(UUID.randomUUID());
        PageResponse<DocumentSummary> page = new PageResponse<>(
                List.of(summary), 0, 20, 1L, 1, true);

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(recommendationService.getRecommendations(eq(currentUser.getId()), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("title"));
    }

    @Test
    void given_pageSizeAboveFifty_when_getRecommendations_then_returns400() throws Exception {
        mockMvc.perform(get("/api/recommendations").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_pageNumberAboveLimit_when_getRecommendations_then_returns400() throws Exception {
        mockMvc.perform(get("/api/recommendations").param("page", "201"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_authenticatedUser_when_postViewInteraction_then_returns204() throws Exception {
        UUID docId = UUID.randomUUID();
        User currentUser = userWithId();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);

        mockMvc.perform(post("/api/documents/{id}/interactions", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"VIEW\"}"))
                .andExpect(status().isNoContent());

        verify(interactionService).logView(currentUser, docId);
    }

    @Test
    void given_kindBookmarkInBody_when_postInteraction_then_returns400() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(post("/api/documents/{id}/interactions", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BOOKMARK\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_unknownDocument_when_postInteraction_then_returns404() throws Exception {
        UUID docId = UUID.randomUUID();
        User currentUser = userWithId();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        doThrow(new DocumentNotFoundException(docId))
                .when(interactionService).logView(currentUser, docId);

        mockMvc.perform(post("/api/documents/{id}/interactions", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"VIEW\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void given_missingBody_when_postInteraction_then_returns400() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(post("/api/documents/{id}/interactions", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static User userWithId() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        return user;
    }

    private static DocumentSummary summaryWithId(UUID id) {
        return new DocumentSummary(
                id, "title", "desc", "ARTICLE", Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.of(),
                false, true, null, null,
                Instant.now(), Instant.now()
        );
    }
}
