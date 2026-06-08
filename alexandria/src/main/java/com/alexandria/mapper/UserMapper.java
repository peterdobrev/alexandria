package com.alexandria.mapper;

import com.alexandria.dto.RegisterRequest;
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

    public UserRole toUserRole(User user, Role role) {
        UserRole userRole = new UserRole();
        userRole.setId(new UserRoleId(null, role.getId()));
        userRole.setUser(user);
        userRole.setRole(role);
        return userRole;
    }
}
