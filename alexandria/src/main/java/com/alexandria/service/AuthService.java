package com.alexandria.service;

import com.alexandria.dto.auth.AuthResponse;
import com.alexandria.dto.auth.LoginRequest;
import com.alexandria.dto.auth.RegisterRequest;
import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.exception.EmailAlreadyInUseException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.RoleRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.JwtService;
import com.alexandria.security.RoleNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid credentials";

    /**
     * A precomputed BCrypt strength-10 hash used to equalize the work performed
     * by {@link #login(LoginRequest)} when the email does not exist, so that a
     * timing side-channel cannot be used to enumerate registered users.
     *
     * <p>The plaintext was a long random string and is not stored anywhere — no
     * password ever matches this hash. The strength must match
     * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}'s
     * default (10) so {@code matches()} doesn't short-circuit on a strength
     * mismatch.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$wXWAULqA66Ep0GmCHJGyOeypvv7.OzLJQKlKTZNd0shQAhgjvDk3C";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final long jwtExpiration;

    public AuthResponse register(RegisterRequest request) {
        Role role = roleRepository.findByName(RoleNames.USER)
                .orElseThrow(() -> new IllegalStateException(
                        "Default role " + RoleNames.USER + " not configured"));

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = userMapper.toUser(request, encodedPassword);
        user.setUserRoles(List.of(userMapper.toUserRole(user, role)));

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("Registration attempt with already-used email: {}", emailHash(request.email()));
            throw new EmailAlreadyInUseException(request.email());
        }

        log.info("User registered successfully: {}", emailHash(request.email()));
        return AuthResponse.of(user.getId(), jwtService.generateToken(user), jwtExpiration);
    }

    public AuthResponse login(LoginRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.email());
        // Always run BCrypt — even when the user is missing — so that response
        // time is constant w.r.t. user existence (defends against enumeration).
        String hashToCheck = userOpt.map(User::getPasswordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordOk = passwordEncoder.matches(request.password(), hashToCheck);

        if (userOpt.isEmpty() || !passwordOk) {
            log.warn("Failed login attempt for email: {}", emailHash(request.email()));
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        User user = userOpt.get();
        log.info("User logged in successfully: {}", emailHash(request.email()));
        return AuthResponse.of(user.getId(), jwtService.generateToken(user), jwtExpiration);
    }

    private static String emailHash(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "[unknown]";
        }
    }
}
