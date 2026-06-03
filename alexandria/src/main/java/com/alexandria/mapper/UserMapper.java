package com.alexandria.mapper;

import com.alexandria.dto.RegisterRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.entity.UserRole;
import com.alexandria.entity.UserRoleId;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;

@RequiredArgsConstructor
public class UserMapper {

    private final PasswordEncoder passwordEncoder;

    public User toUser(RegisterRequest request) {
        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setCreatedAt(Instant.now());
        return user;
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    public UserRole toUserRole(User user, Role role) {
        UserRole userRole = new UserRole();
        userRole.setId(new UserRoleId(null, role.getName()));
        userRole.setUser(user);
        userRole.setRole(role);
        return userRole;
    }
}
