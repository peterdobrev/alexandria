package com.alexandria.controller;

import com.alexandria.dto.AddReadingListItemRequest;
import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.CategorySummary;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.ReadingListService;
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
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class ReadingListControllerTest {

    @Mock
    private ReadingListService readingListService;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private ReadingListController classUnderTest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(classUnderTest)
                .setCustomArgumentResolvers(
                        new PageableHandlerMethodArgumentResolver(),
                        new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void getReadingLists_returns200WithPagedContent() throws Exception {
        User currentUser = new User();
        ReadingListSummaryResponse summary = new ReadingListSummaryResponse(
                UUID.randomUUID(), "My List", Instant.now());

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(readingListService.getReadingLists(any(User.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/reading-lists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("My List"));
    }

    @Test
    void createReadingList_validRequest_returns201() throws Exception {
        User currentUser = new User();
        ReadingListResponse response = readingListResponse();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(readingListService.createReadingList(any(), any(User.class))).thenReturn(response);

        mockMvc.perform(post("/api/reading-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My List\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My List"));
    }

    @Test
    void createReadingList_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/reading-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReadingList_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        ReadingListResponse response = readingListResponse();

        when(readingListService.getReadingList(eq(id))).thenReturn(response);

        mockMvc.perform(get("/api/reading-lists/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My List"));
    }

    @Test
    void updateReadingList_validRequest_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        ReadingListResponse response = new ReadingListResponse(id, "Updated List", Instant.now(), List.of());

        when(readingListService.updateReadingList(eq(id), any())).thenReturn(response);

        mockMvc.perform(put("/api/reading-lists/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated List\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated List"));
    }

    @Test
    void deleteReadingList_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/reading-lists/" + id))
                .andExpect(status().isNoContent());
    }

    @Test
    void addItem_validRequest_returns201() throws Exception {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        DocumentSummary documentSummary = new DocumentSummary(
                docId,
                "Title",
                "A description",
                "PDF",
                Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.<CategorySummary>of(),
                true,
                false,
                123L,
                "application/pdf",
                Instant.now(),
                Instant.now()
        );
        ReadingListItemResponse itemResponse = new ReadingListItemResponse(
                UUID.randomUUID(), documentSummary, Instant.now());

        when(readingListService.addItem(eq(listId), any(AddReadingListItemRequest.class)))
                .thenReturn(itemResponse);

        mockMvc.perform(post("/api/reading-lists/" + listId + "/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + docId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.title").value("Title"));
    }

    @Test
    void removeItem_returns204() throws Exception {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        mockMvc.perform(delete("/api/reading-lists/" + listId + "/items/" + docId))
                .andExpect(status().isNoContent());
    }

    private ReadingListResponse readingListResponse() {
        return new ReadingListResponse(UUID.randomUUID(), "My List", Instant.now(), List.of());
    }
}
