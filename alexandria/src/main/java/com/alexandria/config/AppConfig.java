package com.alexandria.config;

import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.RoleRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.JwtService;
import com.alexandria.security.UserDetailsServiceImpl;
import com.alexandria.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class AppConfig {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Bean
    public UserMapper userMapper() {
        return new UserMapper(passwordEncoder);
    }

    @Bean
    public JwtService jwtService() {
        return new JwtService(jwtSecret, jwtExpiration);
    }

    @Bean
    public UserDetailsServiceImpl userDetailsService() {
        return new UserDetailsServiceImpl(userRepository);
    }

    @Bean
    public AuthService authService() {
        return new AuthService(userRepository, roleRepository, passwordEncoder, jwtService(), userMapper());
    }
}
