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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyInUseException(request.email());
        }

        User user = userMapper.toUser(request);

        Role role = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("Default role not found"));

        user.setUserRoles(List.of(userMapper.toUserRole(user, role)));

        userRepository.save(user);

        return new AuthResponse(jwtService.generateToken(user));
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        return new AuthResponse(jwtService.generateToken(user));
    }
}
