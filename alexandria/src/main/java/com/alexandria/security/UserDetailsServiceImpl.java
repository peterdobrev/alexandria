package com.alexandria.security;

import com.alexandria.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        log.debug("Loading user by username: {}", username);
        return userRepository.findByEmail(username) //username and email are the same thing in our system.
                .map(user -> {
                    List<SimpleGrantedAuthority> authorities = user.getUserRoles().stream()
                            .map(userRole -> new SimpleGrantedAuthority(userRole.getRole().getName()))
                            .toList();

                    return new User(
                            user.getEmail(),
                            user.getPasswordHash(),
                            authorities
                    );
                })
                .orElseThrow(() -> {
                    log.warn("User not found for username: {}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });
    }
}
