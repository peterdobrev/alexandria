package com.alexandria.mapper;

import com.alexandria.dto.RegisterRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.user.UserSummary;
import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.entity.UserRole;
import com.alexandria.entity.UserRoleId;

import java.time.Instant;

public class UserMapper {

    public User toUser(RegisterRequest request, String encodedPassword) {
        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(encodedPassword);
        user.setDisplayName(request.displayName());
        user.setCreatedAt(Instant.now());
        return user;
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    public UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getDisplayName());
    }

    public AuthorSummary toAuthorSummary(User user) {
        return new AuthorSummary(user.getId(), user.getDisplayName());
    }

    public UserRole toUserRole(User user, Role role) {
        UserRole userRole = new UserRole();
        userRole.setId(new UserRoleId(null, role.getId()));
        userRole.setUser(user);
        userRole.setRole(role);
        return userRole;
    }
}
