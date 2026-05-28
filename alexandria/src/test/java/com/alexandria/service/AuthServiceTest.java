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

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private UserMapper userMapper;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, roleRepository, passwordEncoder, jwtService, userMapper);
    }

    @Test
    void register_validRequest_savesUserAndReturnsToken() {
        RegisterRequest request = new RegisterRequest("user@test.com", "password123", "Test User");
        User user = new User();
        Role role = new Role();
        UserRole userRole = new UserRole();

        when(userMapper.toUser(request)).thenReturn(user);
        when(roleRepository.getReferenceById("ROLE_USER")).thenReturn(role);
        when(userMapper.toUserRole(user, role)).thenReturn(userRole);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        verify(userRepository).save(user);
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyInUseException() {
        RegisterRequest request = new RegisterRequest("existing@test.com", "password123", "Test User");
        User user = new User();

        when(userMapper.toUser(request)).thenReturn(user);
        when(roleRepository.getReferenceById("ROLE_USER")).thenReturn(new Role());
        when(userMapper.toUserRole(any(), any())).thenReturn(new UserRole());
        doThrow(new DataIntegrityViolationException("duplicate")).when(userRepository).save(any());

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyInUseException.class)
                .hasMessageContaining("existing@test.com");
    }

    @Test
    void login_validCredentials_returnsToken() {
        LoginRequest request = new LoginRequest("user@test.com", "password123");
        User user = new User();
        user.setPasswordHash("hashed-password");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("jwt-token");
    }

    @Test
    void login_userNotFound_throwsBadCredentialsException() {
        LoginRequest request = new LoginRequest("nobody@test.com", "password123");
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_wrongPassword_throwsBadCredentialsException() {
        LoginRequest request = new LoginRequest("user@test.com", "wrong-password");
        User user = new User();
        user.setPasswordHash("hashed-password");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);
    }
}
