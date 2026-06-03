package com.alexandria.service;

import com.alexandria.dto.UpdateUserRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.User;
import com.alexandria.exception.ForbiddenException;
import com.alexandria.exception.UserNotFoundException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new UserService(userRepository, userMapper, passwordEncoder);
    }

    @Test
    void getUser_existingUser_returnsUserResponse() {
        UUID id = UUID.randomUUID();
        User user = new User();
        UserResponse response = new UserResponse(id, "test@test.com", "Test User", Instant.now());

        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userMapper.toResponse(user)).thenReturn(response);

        assertThat(classUnderTest.getUser(id)).isEqualTo(response);
    }

    @Test
    void getUser_nonExistentUser_throwsUserNotFoundException() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.getUser(id))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateUser_sameUser_updatesDisplayName() {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(id);
        User user = new User();
        user.setId(id);
        UpdateUserRequest request = new UpdateUserRequest("New Name", null);
        UserResponse response = new UserResponse(id, "test@test.com", "New Name", Instant.now());

        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(response);

        assertThat(classUnderTest.updateUser(id, request, currentUser)).isEqualTo(response);
        assertThat(user.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void updateUser_differentUser_throwsForbiddenException() {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        UpdateUserRequest request = new UpdateUserRequest("New Name", null);

        assertThatThrownBy(() -> classUnderTest.updateUser(id, request, currentUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateUser_withPassword_encodesAndSetsPasswordHash() {
        UUID id = UUID.randomUUID();
        User currentUser = new User();
        currentUser.setId(id);
        User user = new User();
        user.setId(id);
        UpdateUserRequest request = new UpdateUserRequest(null, "newpassword");

        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpassword")).thenReturn("encoded-password");
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(new UserResponse(id, "test@test.com", null, Instant.now()));

        classUnderTest.updateUser(id, request, currentUser);

        assertThat(user.getPasswordHash()).isEqualTo("encoded-password");
        verify(passwordEncoder).encode("newpassword");
    }
}
