package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.config.SecurityConfig;
import dev.adrian.goral.localhivebackend.exception.GlobalExceptionHandler;
import dev.adrian.goral.localhivebackend.service.SetupService;
import dev.adrian.goral.localhivebackend.service.WorkerRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WorkerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private WorkerRegistryService workerRegistryService;

    @Test
    @DisplayName("Should return 403 when worker API is called while setup is still required")
    void shouldReturnForbiddenWhenSystemIsInFirstTimeSetupMode() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(true);

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validWorkerRegistrationPayload())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message")
                        .value("System is locked. Please complete the First-Time Config wizard."));

        verify(workerRegistryService, never()).registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        );
    }

    @Test
    @DisplayName("Should return 400 with exact custom validator message for invalid IP literal")
    void shouldReturnBadRequestWhenIpAddressFailsCustomValidator() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        var request = validWorkerRegistrationPayload();
        request.put("ipAddress", "192.168.66.3/24");

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Validation failed for the submitted request."))
                .andExpect(jsonPath("$.fieldErrors.ipAddress")
                        .value("IP address must be a valid IPv4 or IPv6 literal"));

        verify(workerRegistryService, never()).registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        );
    }

    @Test
    @DisplayName("Should return 400 with exact class-level RAM allocation validation message")
    void shouldReturnBadRequestWhenSharedRamExceedsTotalRam() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        var request = validWorkerRegistrationPayload();
        request.put("totalRamMb", 2048);
        request.put("sharedRamMb", 4096);

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.fieldErrors.sharedRamMb")
                        .value("sharedRamMb cannot be greater than totalRamMb"));

        verify(workerRegistryService, never()).registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        );
    }

    @Test
    @DisplayName("Should return 400 with exact max boundary message when cpuCores exceeds limit")
    void shouldReturnBadRequestWhenCpuCoresExceedsMaximumBoundary() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        var request = validWorkerRegistrationPayload();
        request.put("cpuCores", 1025);

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.fieldErrors.cpuCores").value("CPU core count is unrealistically high"));

        verify(workerRegistryService, never()).registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        );
    }

    @Test
    @DisplayName("Should return 409 when service throws IllegalStateException")
    void shouldReturnConflictWhenServiceThrowsIllegalStateException() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);
        when(workerRegistryService.registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        )).thenThrow(new IllegalStateException("Worker with this hostname is already pending approval."));

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validWorkerRegistrationPayload())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker with this hostname is already pending approval."));
    }

    @Test
    @DisplayName("Should return 409 with standardized JSON body when service throws DataIntegrityViolationException")
    void shouldReturnConflictWhenServiceThrowsDataIntegrityViolationException() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);
        when(workerRegistryService.registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        )).thenThrow(new DataIntegrityViolationException("Duplicate key value violates unique constraint"));

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validWorkerRegistrationPayload())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message")
                        .value("A resource with this unique identifier already exists."));
    }

    @Test
    @DisplayName("Should return 500 with standardized JSON body when service throws unexpected exception")
    void shouldReturnInternalServerErrorWhenServiceThrowsUnexpectedException() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);
        when(workerRegistryService.registerNewWorker(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()
        )).thenThrow(new RuntimeException("Unexpected low-level failure"));

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validWorkerRegistrationPayload())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("An unexpected internal server error occurred."));
    }

    @MockitoBean
    private SetupService setupService;

    private static java.util.Map<String, Object> validWorkerRegistrationPayload() {
        return new java.util.HashMap<>(java.util.Map.of(
                "hostname", "Adrian-PC",
                "ipAddress", "192.168.66.3",
                "osType", "Windows 11",
                "totalRamMb", 32768,
                "sharedRamMb", 4096,
                "cpuCores", 16,
                "gpuName", "RTX 5080"
        ));
    }
}