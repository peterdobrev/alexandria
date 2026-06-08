package com.alexandria.service;

import com.alexandria.dto.user.UpdateUserRequest;
import com.alexandria.dto.user.UserSummary;
import com.alexandria.entity.User;
import com.alexandria.exception.UserNotFoundException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String ORIGINAL_DISPLAY_NAME = "Original Name";
    private static final String NEW_DISPLAY_NAME = "New Name";
    private static final String ORIGINAL_PASSWORD_HASH = "original-hash";
    private static final String NEW_PASSWORD = "newpassword";
    private static final String ENCODED_PASSWORD = "encoded-password";

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
    void get_existingUser_returnsUserSummary() {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName(ORIGINAL_DISPLAY_NAME);
        UserSummary expected = new UserSummary(USER_ID, ORIGINAL_DISPLAY_NAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userMapper.toSummary(user)).thenReturn(expected);

        UserSummary result = classUnderTest.get(USER_ID);

        assertThat(result).isEqualTo(expected);
        assertThat(result.id()).isEqualTo(USER_ID);
        assertThat(result.displayName()).isEqualTo(ORIGINAL_DISPLAY_NAME);
    }

    @Test
    void get_nonExistentUser_throwsUserNotFoundException() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.get(USER_ID))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(USER_ID.toString());

        verifyNoInteractions(userMapper);
    }

    @Test
    void update_displayNameOnly_updatesAndReturnsSummary() {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName(ORIGINAL_DISPLAY_NAME);
        user.setPasswordHash(ORIGINAL_PASSWORD_HASH);
        UpdateUserRequest request = new UpdateUserRequest(NEW_DISPLAY_NAME, null);
        UserSummary expected = new UserSummary(USER_ID, NEW_DISPLAY_NAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toSummary(user)).thenReturn(expected);

        UserSummary result = classUnderTest.update(USER_ID, request);

        assertThat(result).isEqualTo(expected);
        assertThat(user.getDisplayName()).isEqualTo(NEW_DISPLAY_NAME);
        assertThat(user.getPasswordHash()).isEqualTo(ORIGINAL_PASSWORD_HASH);
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository).save(user);
    }

    @Test
    void update_passwordOnly_encodesAndSetsPasswordHash() {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName(ORIGINAL_DISPLAY_NAME);
        user.setPasswordHash(ORIGINAL_PASSWORD_HASH);
        UpdateUserRequest request = new UpdateUserRequest(null, NEW_PASSWORD);
        UserSummary expected = new UserSummary(USER_ID, ORIGINAL_DISPLAY_NAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toSummary(user)).thenReturn(expected);

        UserSummary result = classUnderTest.update(USER_ID, request);

        assertThat(result).isEqualTo(expected);
        assertThat(user.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
        assertThat(user.getDisplayName()).isEqualTo(ORIGINAL_DISPLAY_NAME);
        verify(passwordEncoder).encode(NEW_PASSWORD);
        verify(userRepository).save(user);
    }

    @Test
    void update_userNotFound_throwsUserNotFoundException() {
        UpdateUserRequest request = new UpdateUserRequest(NEW_DISPLAY_NAME, null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.update(USER_ID, request))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(USER_ID.toString());

        verifyNoInteractions(userMapper, passwordEncoder);
    }
}
