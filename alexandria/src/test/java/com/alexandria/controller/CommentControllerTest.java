package com.alexandria.controller;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.exception.CommentNotFoundException;
import com.alexandria.exception.GlobalExceptionHandler;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.CommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CommentControllerTest {

    @Mock private CommentService commentService;
    @Mock private SecurityUtils securityUtils;

    @InjectMocks private CommentController classUnderTest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(classUnderTest)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getComments_returns200WithPagedContent() throws Exception {
        UUID docId = UUID.randomUUID();
        CommentResponse response = commentResponse();

        when(commentService.getComments(eq(docId), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/documents/{id}/comments", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].body").value("Nice doc!"));
    }

    @Test
    void addComment_authenticated_returns201() throws Exception {
        UUID docId = UUID.randomUUID();
        CommentResponse response = commentResponse();
        com.alexandria.entity.User currentUser = new com.alexandria.entity.User();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(commentService.addComment(eq(docId), any(), eq(currentUser))).thenReturn(response);

        mockMvc.perform(post("/api/documents/{id}/comments", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Nice doc!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Nice doc!"));
    }

    @Test
    void deleteComment_existingComment_returns204() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/documents/{id}/comments/{commentId}", docId, commentId))
                .andExpect(status().isNoContent());

        verify(commentService).deleteComment(docId, commentId);
    }

    @Test
    void deleteComment_unknownComment_returns404() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        doThrow(new CommentNotFoundException(commentId))
                .when(commentService).deleteComment(docId, commentId);

        mockMvc.perform(delete("/api/documents/{id}/comments/{commentId}", docId, commentId))
                .andExpect(status().isNotFound());
    }

    private static CommentResponse commentResponse() {
        return new CommentResponse(
                UUID.randomUUID(),
                new AuthorSummary(UUID.randomUUID(), "Alice"),
                "Nice doc!",
                Instant.now()
        );
    }
}
