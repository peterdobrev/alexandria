package com.alexandria.mapper;

import com.alexandria.dto.RegisterRequest;
import com.alexandria.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private static final String PASSWORD = "password123";
    private static final String HASHED_PASSWORD = "hashed-password";
    private static final RegisterRequest REGISTER_REQUEST = new RegisterRequest("user@test.com", PASSWORD, "Test User");

    private UserMapper classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new UserMapper();
    }

    @Test
    void toUser_setsHashFromCallerProvidedEncodedPassword() {
        User user = classUnderTest.toUser(REGISTER_REQUEST, HASHED_PASSWORD);

        assertThat(user.getEmail()).isEqualTo(REGISTER_REQUEST.email());
        assertThat(user.getDisplayName()).isEqualTo(REGISTER_REQUEST.displayName());
        assertThat(user.getPasswordHash()).isEqualTo(HASHED_PASSWORD);
        assertThat(user.getCreatedAt()).isNotNull();
    }
}
