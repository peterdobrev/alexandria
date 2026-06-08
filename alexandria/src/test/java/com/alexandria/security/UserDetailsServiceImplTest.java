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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    private static final String TEST_EMAIL = "user@test.com";
    private static final String HASHED_PASSWORD = "hashed-password";

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
        role.setName(RoleNames.USER);

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
                .containsExactly(RoleNames.USER);
    }

    @Test
    void loadUserByUsername_userWithMultipleRoles_returnsAllAuthorities() {
        Role userRoleEntity = new Role();
        userRoleEntity.setName(RoleNames.USER);
        Role adminRoleEntity = new Role();
        adminRoleEntity.setName(RoleNames.ADMIN);

        UserRole link1 = new UserRole();
        link1.setRole(userRoleEntity);
        UserRole link2 = new UserRole();
        link2.setRole(adminRoleEntity);

        User user = new User();
        user.setEmail(TEST_EMAIL);
        user.setPasswordHash(HASHED_PASSWORD);
        user.setUserRoles(List.of(link1, link2));

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        UserDetails userDetails = classUnderTest.loadUserByUsername(TEST_EMAIL);

        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(RoleNames.USER, RoleNames.ADMIN);
    }

    @Test
    void loadUserByUsername_userWithNoRoles_returnsEmptyAuthorities() {
        User user = new User();
        user.setEmail(TEST_EMAIL);
        user.setPasswordHash(HASHED_PASSWORD);
        user.setUserRoles(Collections.emptyList());

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(user));

        UserDetails userDetails = classUnderTest.loadUserByUsername(TEST_EMAIL);

        assertThat(userDetails.getAuthorities()).isEmpty();
    }

    @Test
    void loadUserByUsername_nonExistentUser_throwsUsernameNotFoundException() {
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.loadUserByUsername("nobody@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
