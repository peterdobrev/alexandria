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

    @Mock private UserRepository userRepository;

    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new UserDetailsServiceImpl(userRepository);
    }

    @Test
    void loadUserByUsername_existingUser_returnsCorrectUserDetails() {
        Role role = new Role();
        role.setName("ROLE_USER");

        UserRole userRole = new UserRole();
        userRole.setRole(role);

        User user = new User();
        user.setEmail("user@test.com");
        user.setPasswordHash("hashed-password");
        user.setUserRoles(List.of(userRole));

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("user@test.com");

        assertThat(userDetails.getUsername()).isEqualTo("user@test.com");
        assertThat(userDetails.getPassword()).isEqualTo("hashed-password");
        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    @Test
    void loadUserByUsername_nonExistentUser_throwsUsernameNotFoundException() {
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nobody@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
