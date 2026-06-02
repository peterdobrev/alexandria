package com.alexandria.controller;

import com.alexandria.dto.UpdateUserRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.User;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
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

    @Mock UserService userService;
    @Mock SecurityUtils securityUtils;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService, securityUtils)).build();
    }

    @Test
    void getUser_existingUser_returns200WithBody() throws Exception {
        UUID id = UUID.randomUUID();
        UserResponse response = new UserResponse(id, "test@test.com", "Test User", Instant.now());

        when(userService.getUser(id)).thenReturn(response);

        mockMvc.perform(get("/api/users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@test.com"))
                .andExpect(jsonPath("$.displayName").value("Test User"));
    }

    @Test
    void updateUser_validRequest_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        UpdateUserRequest request = new UpdateUserRequest("New Name", null);
        UserResponse response = new UserResponse(id, "test@test.com", "New Name", Instant.now());

        when(securityUtils.getCurrentUser()).thenReturn(currentUser);
        when(userService.updateUser(eq(id), any(UpdateUserRequest.class), any(User.class))).thenReturn(response);

        mockMvc.perform(put("/api/users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"));
    }

    @Test
    void updateUser_emptyDisplayName_returns400() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
