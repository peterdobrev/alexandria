package com.alexandria.security;

import com.alexandria.entity.Role;
import com.alexandria.entity.User;
import com.alexandria.entity.UserRole;
import com.alexandria.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    private static final String TEST_EMAIL = "user@test.com";
    private static final String HASHED_PASSWORD = "hashed-password";
    private static final String ROLE_USER = "ROLE_USER";

    @Mock
    private UserRepository userRepository;

    private UserDetailsServiceImpl classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new UserDetailsServiceImpl(userRepository);
    }

    @Test
    void loadUserByUsername_existingUser_returnsCorrectUserDetails() {
        Role role = new Role();
        role.setName(ROLE_USER);

        UserRole userRole = new UserRole();
        userRole.setRole(role);

        User user = new User();
        user.setEmail(TEST_EMAIL);
        user.setPasswordHash(HASHED_PASSWORD);
        user.setUserRoles(List.of(userRole));

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        UserDetails userDetails = classUnderTest.loadUserByUsername(TEST_EMAIL);

        assertThat(userDetails.getUsername()).isEqualTo(TEST_EMAIL);
        assertThat(userDetails.getPassword()).isEqualTo(HASHED_PASSWORD);
        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly(ROLE_USER);
    }

    @Test
    void loadUserByUsername_nonExistentUser_throwsUsernameNotFoundException() {
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.loadUserByUsername("nobody@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
