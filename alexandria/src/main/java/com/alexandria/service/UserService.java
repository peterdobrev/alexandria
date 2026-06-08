package com.alexandria.service;

import com.alexandria.dto.user.UpdateUserRequest;
import com.alexandria.dto.user.UserSummary;
import com.alexandria.entity.User;
import com.alexandria.exception.UserNotFoundException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserSummary get(UUID id) {
        return userMapper.toSummary(userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id)));
    }

    public UserSummary update(UUID id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            log.info("Password changed for user: {}", user.getEmail());
        }
        return userMapper.toSummary(userRepository.save(user));
    }
}
