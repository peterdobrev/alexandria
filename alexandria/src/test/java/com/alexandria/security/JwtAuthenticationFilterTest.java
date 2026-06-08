package com.alexandria.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String INVALID_TOKEN = "invalid-token";
    private static final String TEST_EMAIL = "user@test.com";

    @Mock
    private JwtService jwtService;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private FilterChain filterChain;
    @Mock
    private Claims claims;

    @InjectMocks
    private JwtAuthenticationFilter classUnderTest;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_noAuthorizationHeader_continuesChainWithoutAuthentication() throws ServletException, IOException {
        classUnderTest.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_nonBearerHeader_continuesChainWithoutAuthentication() throws ServletException, IOException {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");

        classUnderTest.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtService);
    }

    @Test
    void doFilterInternal_emptyBearerToken_continuesChainWithoutAuthentication() throws ServletException, IOException {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");

        classUnderTest.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_validToken_setsAuthenticationInSecurityContext() throws ServletException, IOException {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
        UserDetails userDetails = new User(TEST_EMAIL, "hashed", List.of());

        when(jwtService.extractClaims(VALID_TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(TEST_EMAIL);
        when(userDetailsService.loadUserByUsername(TEST_EMAIL)).thenReturn(userDetails);

        classUnderTest.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(TEST_EMAIL);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_invalidToken_returns401WithoutContinuingChain() throws ServletException, IOException {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + INVALID_TOKEN);
        when(jwtService.extractClaims(INVALID_TOKEN)).thenThrow(JwtException.class);

        classUnderTest.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_userNotFound_returns401WithoutContinuingChain() throws ServletException, IOException {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);

        when(jwtService.extractClaims(VALID_TOKEN)).thenReturn(claims);
        when(claims.getSubject()).thenReturn("ghost@test.com");
        when(userDetailsService.loadUserByUsername("ghost@test.com")).thenThrow(UsernameNotFoundException.class);

        classUnderTest.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
