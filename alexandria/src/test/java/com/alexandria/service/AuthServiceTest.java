package com.alexandria.service;

import com.alexandria.dto.AuthResponse;
import com.alexandria.dto.LoginRequest;
import com.alexandria.dto.RegisterRequest;
import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.entity.UserRole;
import com.alexandria.exception.EmailAlreadyInUseException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.RoleRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String TEST_EMAIL = "user@test.com";
    private static final String TEST_PASSWORD = "password123";
    private static final String HASHED_PASSWORD = "hashed-password";
    private static final String JWT_TOKEN = "jwt-token";
    private static final String ROLE_USER = "ROLE_USER";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private UserMapper userMapper;

    private AuthService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new AuthService(userRepository, roleRepository, passwordEncoder, jwtService, userMapper);
    }

    @Test
    void register_validRequest_savesUserAndReturnsToken() {
        RegisterRequest request = new RegisterRequest(TEST_EMAIL, TEST_PASSWORD, "Test User");
        User user = new User();
        Role role = new Role();
        UserRole userRole = new UserRole();

        when(userMapper.toUser(request)).thenReturn(user);
        when(roleRepository.findByName(ROLE_USER)).thenReturn(Optional.of(role));
        when(userMapper.toUserRole(user, role)).thenReturn(userRole);
        when(jwtService.generateToken(user)).thenReturn(JWT_TOKEN);

        AuthResponse response = classUnderTest.register(request);

        assertThat(response.token()).isEqualTo(JWT_TOKEN);
        verify(userRepository).save(user);
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyInUseException() {
        RegisterRequest request = new RegisterRequest("existing@test.com", TEST_PASSWORD, "Test User");
        User user = new User();

        when(userMapper.toUser(request)).thenReturn(user);
        when(roleRepository.findByName(ROLE_USER)).thenReturn(Optional.of(new Role()));
        when(userMapper.toUserRole(any(), any())).thenReturn(new UserRole());
        doThrow(new DataIntegrityViolationException("duplicate")).when(userRepository).save(any());

        assertThatThrownBy(() -> classUnderTest.register(request))
                .isInstanceOf(EmailAlreadyInUseException.class)
                .hasMessageContaining("existing@test.com");
    }

    @Test
    void login_validCredentials_returnsToken() {
        LoginRequest request = new LoginRequest(TEST_EMAIL, TEST_PASSWORD);
        User user = new User();
        user.setPasswordHash(HASHED_PASSWORD);

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(TEST_PASSWORD, HASHED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn(JWT_TOKEN);

        AuthResponse response = classUnderTest.login(request);

        assertThat(response.token()).isEqualTo(JWT_TOKEN);
    }

    @Test
    void login_userNotFound_throwsBadCredentialsException() {
        LoginRequest request = new LoginRequest("nobody@test.com", TEST_PASSWORD);
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.login(request))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_wrongPassword_throwsBadCredentialsException() {
        LoginRequest request = new LoginRequest(TEST_EMAIL, "wrong-password");
        User user = new User();
        user.setPasswordHash(HASHED_PASSWORD);

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", HASHED_PASSWORD)).thenReturn(false);

        assertThatThrownBy(() -> classUnderTest.login(request))
                .isInstanceOf(BadCredentialsException.class);
    }
}
