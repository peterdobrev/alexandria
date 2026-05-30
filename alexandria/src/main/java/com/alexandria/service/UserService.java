package com.alexandria.service;

import com.alexandria.dto.UpdateUserRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.User;
import com.alexandria.exception.ForbiddenException;
import com.alexandria.exception.UserNotFoundException;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {
        return userMapper.toResponse(userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id)));
    }

    public UserResponse updateUser(UUID id, UpdateUserRequest request, User currentUser) {
        if (!currentUser.getId().equals(id)) {
            throw new ForbiddenException("You don't have permission to update this user");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        return userMapper.toResponse(userRepository.save(user));
    }
}
