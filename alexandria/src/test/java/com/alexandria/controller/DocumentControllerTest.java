package com.alexandria.controller;

import com.alexandria.dto.CreateDocumentRequest;
import com.alexandria.dto.DocumentResponse;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.dto.UpdateDocumentRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.User;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    @Mock DocumentService documentService;
    @Mock SecurityUtils securityUtils;

    private MockMvc mockMvc;
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentService, securityUtils))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void getDocuments_noFilters_returns200WithPage() throws Exception {
        DocumentSummaryResponse summary = documentSummary();

        when(documentService.getDocuments(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk());
    }

    @Test
    void getDocument_existingDocument_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        DocumentResponse response = documentResponse(id);

        when(documentService.getDocument(id)).thenReturn(response);

        mockMvc.perform(get("/api/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Title"))
                .andExpect(jsonPath("$.type").value("PDF"));
    }

    @Test
    void createDocument_validRequest_returns201() throws Exception {
        User currentUser = new User();
        DocumentResponse response = documentResponse(UUID.randomUUID());

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(documentService.createDocument(any(CreateDocumentRequest.class), any(User.class))).thenReturn(response);

        mockMvc.perform(post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Title\",\"description\":\"Desc\",\"type\":\"PDF\",\"fileUrl\":\"http://example.com/file.pdf\",\"categoryIds\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Title"));
    }

    @Test
    void createDocument_blankTitle_returns400() throws Exception {
        mockMvc.perform(post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"type\":\"PDF\",\"fileUrl\":\"http://example.com/file.pdf\",\"categoryIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDocument_invalidFileUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Title\",\"type\":\"PDF\",\"fileUrl\":\"not-a-url\",\"categoryIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDocument_validRequest_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        DocumentResponse response = documentResponse(id);

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(documentService.updateDocument(eq(id), any(UpdateDocumentRequest.class), any(User.class))).thenReturn(response);

        mockMvc.perform(put("/api/documents/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteDocument_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        User currentUser = new User();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);

        mockMvc.perform(delete("/api/documents/" + id))
                .andExpect(status().isNoContent());
    }

    private DocumentSummaryResponse documentSummary() {
        return new DocumentSummaryResponse(UUID.randomUUID(), "Title", "PDF",
                new UserResponse(UUID.randomUUID(), "author@test.com", "Author", Instant.now()),
                List.of(), Instant.now());
    }

    private DocumentResponse documentResponse(UUID id) {
        return new DocumentResponse(id, "Title", "Desc", "PDF", "http://example.com/file.pdf",
                new UserResponse(UUID.randomUUID(), "author@test.com", "Author", Instant.now()),
                List.of(), Instant.now(), Instant.now());
    }
}
