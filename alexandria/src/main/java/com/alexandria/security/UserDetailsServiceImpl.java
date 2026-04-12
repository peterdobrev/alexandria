package com.alexandria.security;

import com.alexandria.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(user -> {
                    List<SimpleGrantedAuthority> authorities = user.getUserRoles().stream()
                            .map(userRole -> new SimpleGrantedAuthority(userRole.getRole().getName()))
                            .toList();

                    return new org.springframework.security.core.userdetails.User(
                            user.getEmail(),
                            user.getPasswordHash(),
                            authorities
                    );
                })
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
