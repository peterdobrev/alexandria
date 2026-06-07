package com.alexandria.service;

import com.alexandria.dto.AuthResponse;
import com.alexandria.dto.LoginRequest;
import com.alexandria.dto.RegisterRequest;
import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.exception.EmailAlreadyInUseException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.RoleRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_ROLE = "ROLE_USER";
    private static final String INVALID_CREDENTIALS = "Invalid credentials";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    public AuthResponse register(RegisterRequest request) {
        User user = userMapper.toUser(request);
        Role role = roleRepository.findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new RuntimeException(DEFAULT_ROLE + " not found"));
        user.setUserRoles(List.of(userMapper.toUserRole(user, role)));

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("Registration attempt with already-used email: {}", request.email());
            throw new EmailAlreadyInUseException(request.email());
        }

        log.info("User registered successfully: {}", request.email());
        return new AuthResponse(jwtService.generateToken(user));
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login attempt for email: {}", request.email());
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        log.info("User logged in successfully: {}", request.email());
        return new AuthResponse(jwtService.generateToken(user));
    }
}
