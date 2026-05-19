package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.config.SecurityConfig;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.exception.GlobalExceptionHandler;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.security.JwtService;
import dev.adrian.goral.localhivebackend.service.SetupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private SetupService setupService;

    @MockitoBean
    private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @MockitoBean
    private WorkerRepository workerRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private static class LoginPayload {
        public String username;
        public String password;

        LoginPayload(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    @Test
    @DisplayName("POST /api/auth/login - success -> 200 OK with token and user details")
    void login_success_returnsAuthResponse() throws Exception {
        // Given
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = mock(User.class);
        when(user.getUsername()).thenReturn("jdoe");
        when(user.getId()).thenReturn(userId);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(user).getAuthorities();

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);

        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .willReturn(authentication);

        given(jwtService.generateToken(eq(user), eq(userId.toString()), eq("ROLE_USER")))
                .willReturn("jwt-token-123");

        LoginPayload payload = new LoginPayload("jdoe", "secret");

        // When / Then
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").value("jwt-token-123"))
                .andExpect(jsonPath("$.username").value("jdoe"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.userId").value(userId.toString()));

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(jwtService).generateToken(eq(user), eq(userId.toString()), eq("ROLE_USER"));
    }

    @Test
    @DisplayName("POST /api/auth/login - missing username -> 400 BAD REQUEST")
    void login_missingUsername_returnsBadRequest() throws Exception {
        String json = jsonMapper.writeValueAsString(Collections.singletonMap("password", "secret"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists());

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("POST /api/auth/login - missing password -> 400 BAD REQUEST")
    void login_missingPassword_returnsBadRequest() throws Exception {
        String json = jsonMapper.writeValueAsString(Collections.singletonMap("username", "jdoe"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("POST /api/auth/login - empty authorities -> 500 INTERNAL SERVER ERROR (Fail-Secure)")
    void login_emptyAuthorities_throwsResponseStatusException() throws Exception {
        // Given
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        User user = mock(User.class);
        when(user.getUsername()).thenReturn("jdoe");
        when(user.getId()).thenReturn(userId);

        // Return empty authorities to trigger the .orElseThrow logic in the controller
        doReturn(Collections.emptyList()).when(user).getAuthorities();

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);

        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .willReturn(authentication);

        LoginPayload payload = new LoginPayload("jdoe", "secret");

        // When / Then
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(payload)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Authenticated user has no granted authorities"));

        verify(jwtService, never()).generateToken(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /api/auth/login - null authorities -> 500 INTERNAL SERVER ERROR mapped by GlobalExceptionHandler")
    void login_nullAuthorities_throwsNullPointerException() throws Exception {
        // Given
        User user = mock(User.class);
        when(user.getUsername()).thenReturn("jdoe");

        // Returning null will trigger Objects.requireNonNull
        doReturn(null).when(user).getAuthorities();

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);

        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .willReturn(authentication);

        LoginPayload payload = new LoginPayload("jdoe", "secret");

        // When / Then
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(payload)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected internal server error occurred."));

        verify(jwtService, never()).generateToken(any(), anyString(), anyString());
    }
}