package com.alexandria.controller;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.CreateArticleRequest;
import com.alexandria.dto.document.DocumentDetail;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.dto.document.UpdateDocumentRequest;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.DocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    @Mock
    private DocumentService documentService;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private DocumentController classUnderTest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(classUnderTest)
                .setCustomArgumentResolvers(
                        new PageableHandlerMethodArgumentResolver(),
                        new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_anonymous_returns200WithPage() throws Exception {
        DocumentSummary summary = documentSummary();
        PageResponse<DocumentSummary> page = new PageResponse<>(
                List.of(summary), 0, 20, 1L, 1, true);

        when(documentService.list(any(DocumentService.DocumentFilters.class), any(), eq(null)))
                .thenReturn(page);

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Title"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void get_existingDocument_returns200WithBody() throws Exception {
        UUID id = UUID.randomUUID();
        DocumentDetail detail = documentDetail(id);

        when(documentService.get(eq(id), eq(null))).thenReturn(detail);

        mockMvc.perform(get("/api/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Title"))
                .andExpect(jsonPath("$.type").value("ARTICLE"));
    }

    @Test
    void createArticle_validRequest_returns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        DocumentDetail detail = documentDetail(id);

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(documentService.createArticle(any(CreateArticleRequest.class), eq(currentUser.getId())))
                .thenReturn(detail);

        mockMvc.perform(post("/api/documents/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Title\",\"description\":\"Desc\",\"type\":\"ARTICLE\","
                                + "\"body\":\"Body content\",\"categoryIds\":[],\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/documents/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Title"));
    }

    @Test
    void createArticle_blankTitle_returns400() throws Exception {
        mockMvc.perform(post("/api/documents/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"type\":\"ARTICLE\",\"body\":\"Body\",\"categoryIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_validRequest_returns200WithBody() throws Exception {
        UUID id = UUID.randomUUID();
        DocumentDetail detail = documentDetail(id);

        when(documentService.update(eq(id), any(UpdateDocumentRequest.class))).thenReturn(detail);

        mockMvc.perform(put("/api/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void delete_authenticatedNonAdmin_returns204AndCallsServiceWithIsAdminFalse() throws Exception {
        UUID id = UUID.randomUUID();
        UserDetails principal = new org.springframework.security.core.userdetails.User(
                "a@b.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "pw", principal.getAuthorities()));

        mockMvc.perform(delete("/api/documents/" + id))
                .andExpect(status().isNoContent());

        ArgumentCaptor<Boolean> isAdminCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(documentService).delete(eq(id), isAdminCaptor.capture());
        assertThat(isAdminCaptor.getValue()).isFalse();
    }

    private DocumentSummary documentSummary() {
        return new DocumentSummary(
                UUID.randomUUID(),
                "Title",
                "Desc",
                "ARTICLE",
                Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.of(),
                false,
                true,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private DocumentDetail documentDetail(UUID id) {
        return new DocumentDetail(
                id,
                "Title",
                "Desc",
                "ARTICLE",
                Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.of(),
                false,
                true,
                null,
                null,
                "Body content",
                Instant.now(),
                Instant.now());
    }
}
