package com.alexandria.config;

import com.alexandria.mapper.CategoryMapper;
import com.alexandria.mapper.CommentMapper;
import com.alexandria.mapper.DocumentMapper;
import com.alexandria.mapper.ReadingListMapper;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.RoleRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.JwtService;
import com.alexandria.security.UserDetailsServiceImpl;
import com.alexandria.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    @Value("${jwt.issuer}")
    private String jwtIssuer;

    @Value("${jwt.audience}")
    private String jwtAudience;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public UserMapper userMapper() {
        return new UserMapper();
    }

    @Bean
    public DocumentMapper documentMapper() {
        return new DocumentMapper();
    }

    @Bean
    public CategoryMapper categoryMapper() {
        return new CategoryMapper();
    }

    @Bean
    public CommentMapper commentMapper() {
        return new CommentMapper(userMapper());
    }

    @Bean
    public ReadingListMapper readingListMapper() {
        return new ReadingListMapper(documentMapper());
    }

    @Bean
    public JwtService jwtService() {
        return new JwtService(jwtSecret, jwtExpiration, jwtIssuer, jwtAudience);
    }

    @Bean
    public UserDetailsServiceImpl userDetailsService() {
        return new UserDetailsServiceImpl(userRepository);
    }

    @Bean
    public AuthService authService() {
        return new AuthService(userRepository, roleRepository, passwordEncoder, jwtService(), userMapper(), jwtExpiration);
    }
}
