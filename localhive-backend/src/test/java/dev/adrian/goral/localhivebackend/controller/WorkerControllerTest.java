package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.config.SecurityConfig;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.exception.GlobalExceptionHandler;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.security.ApiErrorResponseWriter;
import dev.adrian.goral.localhivebackend.security.JwtService;
import dev.adrian.goral.localhivebackend.service.SetupService;
import dev.adrian.goral.localhivebackend.service.WorkerRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ApiErrorResponseWriter.class})
class WorkerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private WorkerRegistryService workerRegistryService;

    @Test
    @DisplayName("Should return 423 when worker API is called while setup is still required")
    void shouldReturnLockedWhenSystemIsInFirstTimeSetupMode() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(true);

        // When + Then
        mockMvc.perform(post("/api/workers/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(validWorkerRegistrationPayload())))
                .andExpect(status().is(423))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message")
                        .value("System is locked. Please complete the First-Time Config wizard."))
                .andExpect(jsonPath("$.timestamp").exists());

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

    @Test
    @DisplayName("Should return 200 and updated payload when worker changes only shared RAM allocation")
    void shouldReturnOkWhenWorkerUpdatesSharedRamAllocation() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var updatedWorker = authenticatedWorker(workerId, apiKeyHash);
        updatedWorker.setSharedRamMb(8192);
        when(workerRegistryService.updateWorkerAllocation(workerId, 8192)).thenReturn(updatedWorker);

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(java.util.Map.of("sharedRamMb", 8192))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workerId.toString()))
                .andExpect(jsonPath("$.sharedRamMb").value(8192))
                .andExpect(jsonPath("$.totalRamMb").value(32768));

        verify(workerRegistryService).updateWorkerAllocation(workerId, 8192);
    }

    @Test
    @DisplayName("Should return 400 when allocation endpoint receives missing shared RAM")
    void shouldReturnBadRequestWhenAllocationPayloadIsMissingSharedRam() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Validation failed for the submitted request."))
                .andExpect(jsonPath("$.fieldErrors.sharedRamMb").value("Shared RAM must be provided"));

        verify(workerRegistryService, never()).updateWorkerAllocation(any(), any());
    }

    @Test
    @DisplayName("Should return 200 when worker reports a hardware replacement with total RAM and shared RAM together")
    void shouldReturnOkWhenWorkerUpdatesHardwareSpecAndSharedRamTogether() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var updatedWorker = authenticatedWorker(workerId, apiKeyHash);
        updatedWorker.setHostname("worker-updated");
        updatedWorker.setOsType("Windows 11 Pro");
        updatedWorker.setTotalRamMb(65536);
        updatedWorker.setSharedRamMb(8192);
        updatedWorker.setCpuCores(32);
        updatedWorker.setGpuName("RTX 5090");
        when(workerRegistryService.updateWorkerHardwareSpec(any(), any())).thenReturn(updatedWorker);

        var request = java.util.Map.of(
                "hostname", "worker-updated",
                "osType", "Windows 11 Pro",
                "totalRamMb", 65536,
                "sharedRamMb", 8192,
                "cpuCores", 32,
                "gpuName", "RTX 5090"
        );

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/spec", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("worker-updated"))
                .andExpect(jsonPath("$.osType").value("Windows 11 Pro"))
                .andExpect(jsonPath("$.totalRamMb").value(65536))
                .andExpect(jsonPath("$.sharedRamMb").value(8192))
                .andExpect(jsonPath("$.cpuCores").value(32))
                .andExpect(jsonPath("$.gpuName").value("RTX 5090"));

        verify(workerRegistryService).updateWorkerHardwareSpec(any(), any());
    }

    @Test
    @DisplayName("Should return 400 when hardware spec changes total RAM without shared RAM")
    void shouldReturnBadRequestWhenHardwareSpecChangesTotalRamWithoutSharedRam() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);
        when(workerRegistryService.updateWorkerHardwareSpec(any(), any()))
                .thenThrow(new IllegalStateException("sharedRamMb must be provided when totalRamMb changes."));

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/spec", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(java.util.Map.of("totalRamMb", 65536))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("sharedRamMb must be provided when totalRamMb changes."));

        verify(workerRegistryService).updateWorkerHardwareSpec(any(), any());
    }

    @Test
    @DisplayName("Should return 404 when worker does not exist for allocation update")
    void shouldReturnNotFoundWhenWorkerDoesNotExistForAllocationUpdate() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);
        when(workerRegistryService.updateWorkerAllocation(workerId, 8192))
                .thenThrow(new IllegalArgumentException("Worker not found with ID: " + workerId));

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(java.util.Map.of("sharedRamMb", 8192))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker not found with ID: " + workerId));

        verify(workerRegistryService).updateWorkerAllocation(workerId, 8192);
    }

    @Test
    @DisplayName("Should return 401 when API key does not match worker record")
    void shouldReturnUnauthorizedWhenApiKeyDoesNotMatchWorker() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, "hashed-api-key")));
        when(passwordEncoder.matches(apiKey, "hashed-api-key")).thenReturn(false);

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                .header("X-API-KEY", apiKey)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(java.util.Map.of("sharedRamMb", 8192))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString(apiKey))));

        verify(workerRegistryService, never()).updateWorkerAllocation(any(), any());
    }

    @Test
    @DisplayName("Should return 401 JSON when worker is unknown")
    void shouldReturnUnauthorizedJsonWhenWorkerIsUnknown() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.empty());

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(java.util.Map.of("sharedRamMb", 8192))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString(apiKey))));

        verify(workerRegistryService, never()).updateWorkerAllocation(any(), any());
    }

    @Test
    @DisplayName("Should return 401 when pending worker attempts authenticated worker API")
    void shouldReturnUnauthorizedWhenPendingWorkerAttemptsAuthenticatedApi() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(pendingWorker(workerId, apiKeyHash)));

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                .header("X-API-KEY", apiKey)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(java.util.Map.of("sharedRamMb", 8192))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString(apiKey))));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(workerRegistryService, never()).updateWorkerAllocation(any(), any());
    }

    @Test
    @DisplayName("Should return 401 JSON when approved worker has no stored API key hash")
    void shouldReturnUnauthorizedJsonWhenApprovedWorkerHasNoStoredApiKeyHash() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, null)));

        // When + Then
        mockMvc.perform(patch("/api/workers/{workerId}/allocation", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(java.util.Map.of("sharedRamMb", 8192))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString(apiKey))));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(workerRegistryService, never()).updateWorkerAllocation(any(), any());
    }

    @Test
    @DisplayName("Should return 200 when heartbeat request is valid with pauseEnabled and sharedRamMb")
    void shouldReturnOkWhenHeartbeatIsValid() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        // Mock the filter's workerRepository.findById() and passwordEncoder.matches()
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 4096
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(workerRegistryService).handleHeartbeat(
                eq(workerId),
                eq("worker-api-key"),
                argThat(dto -> dto.pauseEnabled() == false
                        && dto.sharedRamMb() == 4096
                        && dto.capabilities() == null)
        );
    }

    @Test
    @DisplayName("Should return 200 when heartbeat includes worker capabilities")
    void shouldReturnOkWhenHeartbeatIncludesCapabilities() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 4096,
                "capabilities", java.util.Map.of(
                        "executors", java.util.List.of(
                                java.util.Map.of(
                                        "executorId", "localhive.no-op",
                                        "executorContractVersion", 1,
                                        "enabled", true
                                ),
                                java.util.Map.of(
                                        "executorId", "localhive.docker.workload",
                                        "executorContractVersion", 1,
                                        "enabled", true
                                )
                        ),
                        "docker", java.util.Map.of(
                                "enabled", true,
                                "allowedImages", java.util.List.of("alpine:3.20"),
                                "maxMemoryMb", 4096,
                                "maxCpuCores", 8,
                                "gpuAllowed", false
                        )
                )
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(workerRegistryService).handleHeartbeat(
                eq(workerId),
                eq("worker-api-key"),
                argThat(dto -> dto.pauseEnabled() == false
                        && dto.sharedRamMb() == 4096
                        && dto.capabilities() != null
                        && dto.capabilities().executors().size() == 2
                        && "localhive.no-op".equals(dto.capabilities().executors().get(0).executorId())
                        && dto.capabilities().executors().get(0).executorContractVersion() == 1
                        && Boolean.TRUE.equals(dto.capabilities().executors().get(0).enabled())
                        && Boolean.TRUE.equals(dto.capabilities().docker().enabled())
                        && dto.capabilities().docker().allowedImages().equals(java.util.List.of("alpine:3.20"))
                        && dto.capabilities().docker().maxMemoryMb() == 4096
                        && dto.capabilities().docker().maxCpuCores() == 8
                        && Boolean.FALSE.equals(dto.capabilities().docker().gpuAllowed()))
        );
    }

    @Test
    @DisplayName("Should return 401 when heartbeat request is missing X-API-KEY header")
    void shouldReturnUnauthorizedWhenHeartbeatMissingApiKey() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 4096
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 401 JSON when heartbeat API key is blank")
    void shouldReturnUnauthorizedWhenHeartbeatApiKeyIsBlank() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 4096
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", " ")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 401 JSON when worker ID path segment is malformed")
    void shouldReturnUnauthorizedWhenWorkerIdPathSegmentIsMalformed() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 4096
        );

        // When + Then
        mockMvc.perform(post("/api/workers/not-a-uuid/heartbeat")
                        .header("X-API-KEY", "worker-api-key")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Worker authentication failed."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when heartbeat request is missing pauseEnabled field")
    void shouldReturnBadRequestWhenHeartbeatMissingPauseEnabled() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        // Mock the filter's authentication
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "sharedRamMb", 4096
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when heartbeat request is missing sharedRamMb field")
    void shouldReturnBadRequestWhenHeartbeatMissingSharedRamMb() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        // Mock the filter's authentication
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when heartbeat sharedRamMb is negative")
    void shouldReturnBadRequestWhenHeartbeatSharedRamMbIsNegative() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        // Mock the filter's authentication
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", -100
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.fieldErrors.sharedRamMb").value("sharedRamMb cannot be negative"));

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when heartbeat sharedRamMb exceeds maximum")
    void shouldReturnBadRequestWhenHeartbeatSharedRamMbExceedsMaximum() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        // Mock the filter's authentication
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 10_485_761
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.fieldErrors.sharedRamMb").value("sharedRamMb is unrealistically high"));

        verify(workerRegistryService, never()).handleHeartbeat(any(), any(), any());
    }

    @Test
    @DisplayName("Should update worker pause state when heartbeat with pauseEnabled=true")
    void shouldUpdateWorkerPauseStateWhenHeartbeatHasPauseEnabled() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        // Mock the filter's authentication
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(authenticatedWorker(workerId, apiKeyHash)));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", true,
                "sharedRamMb", 8192
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(workerRegistryService).handleHeartbeat(
                eq(workerId),
                eq("worker-api-key"),
                argThat(dto -> dto.pauseEnabled() == true && dto.sharedRamMb() == 8192)
        );
    }

    @Test
    @DisplayName("Should allow heartbeat for approved offline worker")
    void shouldRecoverWorkerFromOfflineStatusWhenHeartbeatReceived() throws Exception {
        // Given
        when(setupService.isSetupRequired()).thenReturn(false);

        UUID workerId = UUID.randomUUID();
        String apiKey = "worker-api-key";
        String apiKeyHash = "hashed-api-key";

        Worker offlineWorker = Worker.builder()
                .id(workerId)
                .hostname("offline-worker")
                .ipAddress("192.168.1.10")
                .osType("Windows 11")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.OFFLINE)
                .availabilityStatus(WorkerAvailabilityStatus.PAUSED)
                .apiKeyHash(apiKeyHash)
                .build();

        // Mock the filter's authentication
        when(workerRepository.findById(workerId)).thenReturn(Optional.of(offlineWorker));
        when(passwordEncoder.matches(apiKey, apiKeyHash)).thenReturn(true);

        var heartbeatRequest = java.util.Map.of(
                "pauseEnabled", false,
                "sharedRamMb", 4096
        );

        // When + Then
        mockMvc.perform(post("/api/workers/{workerId}/heartbeat", workerId)
                        .header("X-API-KEY", apiKey)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        // Verify the worker state was updated
        verify(workerRegistryService).handleHeartbeat(
                eq(workerId),
                eq("worker-api-key"),
                argThat(dto -> dto.pauseEnabled() == false && dto.sharedRamMb() == 4096)
        );
    }

    @MockitoBean
    private SetupService setupService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @MockitoBean
    private WorkerRepository workerRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

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

    private static Worker authenticatedWorker(UUID workerId, String apiKeyHash) {
        return Worker.builder()
                .id(workerId)
                .hostname("worker-1")
                .ipAddress("192.168.1.10")
                .osType("Windows 11")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .apiKeyHash(apiKeyHash)
                .build();
    }

    private static Worker pendingWorker(UUID workerId, String apiKeyHash) {
        return Worker.builder()
                .id(workerId)
                .hostname("pending-worker")
                .ipAddress("192.168.1.10")
                .osType("Windows 11")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.PENDING)
                .connectionStatus(WorkerConnectionStatus.OFFLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .apiKeyHash(apiKeyHash)
                .build();
    }
}
