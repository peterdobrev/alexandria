package com.alexandria.mapper;

import com.alexandria.dto.RegisterRequest;
import com.alexandria.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMapperTest {

    private static final String PASSWORD = "password123";
    private static final String HASHED_PASSWORD = "hashed-password";
    private static final RegisterRequest REGISTER_REQUEST = new RegisterRequest("user@test.com", PASSWORD, "Test User");

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserMapper classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new UserMapper(passwordEncoder);
    }

    @Test
    void toUser_encodesPassword() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED_PASSWORD);

        User user = classUnderTest.toUser(REGISTER_REQUEST);

        verify(passwordEncoder).encode(PASSWORD);
        assertThat(user.getPasswordHash()).isEqualTo(HASHED_PASSWORD);
    }
}
