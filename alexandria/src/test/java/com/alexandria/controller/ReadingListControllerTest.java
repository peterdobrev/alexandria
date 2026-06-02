package com.alexandria.controller;

import com.alexandria.dto.AddReadingListItemRequest;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.User;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.ReadingListService;
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
class ReadingListControllerTest {

    @Mock ReadingListService readingListService;
    @Mock SecurityUtils securityUtils;

    private MockMvc mockMvc;
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReadingListController(readingListService, securityUtils))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void getReadingLists_returns200() throws Exception {
        User currentUser = new User();
        ReadingListSummaryResponse summary = new ReadingListSummaryResponse(UUID.randomUUID(), "My List", Instant.now());

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(readingListService.getReadingLists(any(User.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/reading-lists"))
                .andExpect(status().isOk());
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
        User currentUser = new User();
        ReadingListResponse response = readingListResponse();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(readingListService.getReadingList(eq(id), any(User.class))).thenReturn(response);

        mockMvc.perform(get("/api/reading-lists/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My List"));
    }

    @Test
    void updateReadingList_validRequest_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        ReadingListResponse response = readingListResponse();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(readingListService.updateReadingList(eq(id), any(), any(User.class))).thenReturn(response);

        mockMvc.perform(put("/api/reading-lists/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated List\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReadingList_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        User currentUser = new User();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);

        mockMvc.perform(delete("/api/reading-lists/" + id))
                .andExpect(status().isNoContent());
    }

    @Test
    void addItem_validRequest_returns201() throws Exception {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        User currentUser = new User();
        ReadingListItemResponse itemResponse = new ReadingListItemResponse(UUID.randomUUID(),
                new DocumentSummaryResponse(docId, "Title", "PDF",
                        new UserResponse(UUID.randomUUID(), "a@test.com", "Author", Instant.now()),
                        List.of(), Instant.now()),
                Instant.now());

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(readingListService.addItem(eq(listId), any(AddReadingListItemRequest.class), any(User.class)))
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
        User currentUser = new User();

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);

        mockMvc.perform(delete("/api/reading-lists/" + listId + "/items/" + docId))
                .andExpect(status().isNoContent());
    }

    private ReadingListResponse readingListResponse() {
        return new ReadingListResponse(UUID.randomUUID(), "My List", Instant.now(), List.of());
    }
}
