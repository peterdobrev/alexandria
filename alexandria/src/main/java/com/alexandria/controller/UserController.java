package com.alexandria.controller;

import com.alexandria.dto.user.UpdateUserRequest;
import com.alexandria.dto.user.UserSummary;
import com.alexandria.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public ResponseEntity<UserSummary> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.get(id));
    }

    @PreAuthorize("@ownership.isSelf(#id, principal)")
    @PutMapping("/{id}")
    public ResponseEntity<UserSummary> updateUser(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }
}
