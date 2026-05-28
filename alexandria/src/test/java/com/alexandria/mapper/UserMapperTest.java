package com.alexandria.mapper;

import com.alexandria.dto.RegisterRequest;
import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMapperTest {

    @Mock private PasswordEncoder passwordEncoder;

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = new UserMapper(passwordEncoder);
    }

    @Test
    void toUser_mapsAllFieldsFromRegisterRequest() {
        RegisterRequest request = new RegisterRequest("user@test.com", "password123", "Test User");
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");

        User user = userMapper.toUser(request);

        assertThat(user.getEmail()).isEqualTo("user@test.com");
        assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(user.getDisplayName()).isEqualTo("Test User");
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test
    void toUserRole_setsUserRoleAndIdCorrectly() {
        User user = new User();
        Role role = new Role();
        role.setName("ROLE_USER");

        UserRole userRole = userMapper.toUserRole(user, role);

        assertThat(userRole.getUser()).isEqualTo(user);
        assertThat(userRole.getRole()).isEqualTo(role);
        assertThat(userRole.getId().getRoleName()).isEqualTo("ROLE_USER");
        assertThat(userRole.getId().getUserId()).isNull();
    }
}
