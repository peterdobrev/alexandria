package com.alexandria.controller;

import com.alexandria.dto.user.UpdateUserRequest;
import com.alexandria.dto.user.UserSummary;
import com.alexandria.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController classUnderTest;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(classUnderTest).build();
    }

    @Test
    void getUser_existingUser_returns200WithBody() throws Exception {
        UUID id = UUID.randomUUID();
        UserSummary summary = new UserSummary(id, "Test User");

        when(userService.get(id)).thenReturn(summary);

        mockMvc.perform(get("/api/users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.displayName").value("Test User"));
    }

    @Test
    void updateUser_validRequest_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateUserRequest request = new UpdateUserRequest("New Name", null);
        UserSummary summary = new UserSummary(id, "New Name");

        when(userService.update(eq(id), any(UpdateUserRequest.class))).thenReturn(summary);

        mockMvc.perform(put("/api/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.displayName").value("New Name"));
    }

    @Test
    void updateUser_shortPassword_returns400() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
}
