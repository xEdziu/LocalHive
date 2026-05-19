package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.config.SecurityConfig;
import dev.adrian.goral.localhivebackend.exception.GlobalExceptionHandler;
import dev.adrian.goral.localhivebackend.security.JwtService;
import dev.adrian.goral.localhivebackend.service.SetupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SetupController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SetupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private SetupService setupService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @Test
    @DisplayName("Should return 409 when setup endpoint is locked by interceptor")
    void shouldReturnConflictWhenSetupEndpointIsLockedByInterceptor() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        // When + Then
        mockMvc.perform(post("/api/setup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validSetupPayload())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("System is already configured. Setup wizard is locked."));

        verify(setupService, never()).completeFirstTimeSetup(anyString(), anyString());
    }

    @Test
    @DisplayName("Should return 400 with exact username regex validation message")
    void shouldReturnBadRequestWhenUsernameContainsForbiddenCharacters() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(true);

        var request = validSetupPayload();
        request.put("username", "Admin!* ");

        // When + Then
        mockMvc.perform(post("/api/setup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Validation failed for the submitted request."))
                .andExpect(jsonPath("$.fieldErrors.username")
                        .value("Username can contain only letters, digits, dot, underscore and hyphen"));

        verify(setupService, never()).completeFirstTimeSetup(anyString(), anyString());
    }

    @Test
    @DisplayName("Should return 400 with exact password complexity validation message")
    void shouldReturnBadRequestWhenPasswordDoesNotMatchComplexityPattern() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(true);

        var request = validSetupPayload();
        request.put("password", "alllowercase123");

        // When + Then
        mockMvc.perform(post("/api/setup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Validation failed for the submitted request."))
                .andExpect(jsonPath("$.fieldErrors.password")
                        .value("Password must contain upper, lower, digit and special character"));

        verify(setupService, never()).completeFirstTimeSetup(anyString(), anyString());
    }

    @Test
    @DisplayName("Should return 400 with exact NotBlank message when username is null")
    void shouldReturnBadRequestWhenUsernameIsMissing() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(true);

        var request = validSetupPayload();
        request.put("username", null);

        // When + Then
        mockMvc.perform(post("/api/setup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.fieldErrors.username").value("Username cannot be blank"));

        verify(setupService, never()).completeFirstTimeSetup(anyString(), anyString());
    }

    @Test
    @DisplayName("Should return 409 when setup service reports the system is already configured")
    void shouldReturnConflictWhenServiceThrowsIllegalStateException() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(true);
        doThrow(new IllegalStateException("System is already configured. Cannot run setup again."))
                .when(setupService)
                .completeFirstTimeSetup(anyString(), anyString());

        // When + Then
        mockMvc.perform(post("/api/setup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validSetupPayload())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("System is already configured. Cannot run setup again."));
    }

    @Test
    @DisplayName("Should return 500 when interceptor cannot read setup state")
    void shouldReturnInternalServerErrorWhenInterceptorFailsToCheckSetupState() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenThrow(new RuntimeException("Database unavailable"));

        // When + Then
        mockMvc.perform(get("/api/setup/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Internal configuration check failed."));
    }

    private static java.util.Map<String, Object> validSetupPayload() {
        return new java.util.HashMap<>(java.util.Map.of(
                "username", "Admin_01",
                "password", "StrongPassword123!"
        ));
    }
}