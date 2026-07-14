package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.config.SecurityConfig;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.exception.GlobalExceptionHandler;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.security.JwtService;
import dev.adrian.goral.localhivebackend.service.SetupService;
import dev.adrian.goral.localhivebackend.service.WorkerRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminWorkerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminWorkerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkerRegistryService workerRegistryService;

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

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_ROLE = "ADMIN";

    // ==================== GET /api/admin/workers ====================

    @Test
    @DisplayName("Should return 200 with list of all workers when database contains multiple workers")
    void shouldReturnAllWorkersWhenDatabaseContainsMultipleWorkers() throws Exception {
        // Given
        UUID workerId1 = UUID.randomUUID();
        UUID workerId2 = UUID.randomUUID();

        Worker worker1 = Worker.builder()
                .id(workerId1)
                .hostname("worker-1")
                .ipAddress("192.168.1.10")
                .osType("Ubuntu 20.04")
                .totalRamMb(16384)
                .sharedRamMb(2048)
                .cpuCores(8)
                .gpuName("RTX 3070")
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .lastHeartbeatAt(LocalDateTime.now())
                .build();

        Worker worker2 = Worker.builder()
                .id(workerId2)
                .hostname("worker-2")
                .ipAddress("192.168.1.11")
                .osType("Windows Server 2019")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.PENDING)
                .connectionStatus(WorkerConnectionStatus.OFFLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .lastHeartbeatAt(null)
                .build();

        when(workerRegistryService.getAllWorkers()).thenReturn(List.of(worker1, worker2));

        // When + Then
        mockMvc.perform(get("/api/admin/workers")
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(workerId1.toString()))
                .andExpect(jsonPath("$[0].hostname").value("worker-1"))
                .andExpect(jsonPath("$[0].ipAddress").value("192.168.1.10"))
                .andExpect(jsonPath("$[0].osType").value("Ubuntu 20.04"))
                .andExpect(jsonPath("$[0].totalRamMb").value(16384))
                .andExpect(jsonPath("$[0].sharedRamMb").value(2048))
                .andExpect(jsonPath("$[0].cpuCores").value(8))
                .andExpect(jsonPath("$[0].gpuName").value("RTX 3070"))
                .andExpect(jsonPath("$[0].approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$[0].availabilityStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].id").value(workerId2.toString()))
                .andExpect(jsonPath("$[1].hostname").value("worker-2"))
                .andExpect(jsonPath("$[1].approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$[1].availabilityStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));

        verify(workerRegistryService).getAllWorkers();
    }

    @Test
    @DisplayName("Should return 200 with empty list when no workers are registered")
    void shouldReturnEmptyListWhenNoDatabaseWorkersExist() throws Exception {
        // Given
        when(workerRegistryService.getAllWorkers()).thenReturn(List.of());

        // When + Then
        mockMvc.perform(get("/api/admin/workers")
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(workerRegistryService).getAllWorkers();
    }

    @Test
    @DisplayName("Should return 200 with correct worker status transitions in list")
    void shouldReturnWorkersWithDifferentStatusesInList() throws Exception {
        // Given
        UUID activeWorkerId = UUID.randomUUID();
        UUID offlineWorkerId = UUID.randomUUID();

        Worker activeWorker = Worker.builder()
                .id(activeWorkerId)
                .hostname("active-worker")
                .ipAddress("10.0.0.1")
                .osType("Linux")
                .totalRamMb(8192)
                .sharedRamMb(1024)
                .cpuCores(4)
                .gpuName(null)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .lastHeartbeatAt(LocalDateTime.now().minusMinutes(5))
                .build();

        Worker offlineWorker = Worker.builder()
                .id(offlineWorkerId)
                .hostname("offline-worker")
                .ipAddress("10.0.0.2")
                .osType("Linux")
                .totalRamMb(4096)
                .sharedRamMb(512)
                .cpuCores(2)
                .gpuName(null)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.OFFLINE)
                .availabilityStatus(WorkerAvailabilityStatus.PAUSED)
                .lastHeartbeatAt(LocalDateTime.now().minusHours(2))
                .build();

        when(workerRegistryService.getAllWorkers()).thenReturn(List.of(activeWorker, offlineWorker));

        // When + Then
        mockMvc.perform(get("/api/admin/workers")
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.status=='ACTIVE')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.status=='OFFLINE')]", hasSize(1)));

        verify(workerRegistryService).getAllWorkers();
    }

    // ==================== POST /api/admin/workers/{workerId}/approve ====================

    @Test
    @DisplayName("Should return 200 with success message when worker is successfully approved")
    void shouldReturnSuccessWhenWorkerIsApprovedSuccessfully() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doReturn("raw-api-key-123").when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Worker has been approved."));

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should return 404 when worker ID does not exist in database")
    void shouldReturnNotFoundWhenWorkerDoesNotExist() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("Worker not found with ID: " + workerId))
                .when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("Worker not found with ID")));

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should return 400 when worker is not pending approval")
    void shouldReturnBadRequestWhenWorkerIsNotPending() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doThrow(new IllegalStateException(
                "Worker is not in PENDING approval status. Current approvalStatus: APPROVED"
        ))
                .when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("Worker is not in PENDING approval status")));

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should return 400 when attempting to approve already approved worker")
    void shouldReturnBadRequestWhenApprovingAlreadyActiveWorker() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doThrow(new IllegalStateException(
                "Worker is not in PENDING approval status. Current approvalStatus: APPROVED"
        ))
                .when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("Current approvalStatus: APPROVED")));

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should return 400 when attempting to approve a worker that is not pending approval")
    void shouldReturnBadRequestWhenApprovingOfflineWorker() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doThrow(new IllegalStateException(
                "Worker is not in PENDING approval status. Current approvalStatus: APPROVED"
        ))
                .when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("Current approvalStatus: APPROVED")));

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should verify service method is called with correct worker ID parameter")
    void shouldCallServiceMethodWithCorrectWorkerId() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doReturn("raw-api-key-456").when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf()))
                .andExpect(status().isOk());

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should return 500 when service throws unexpected RuntimeException")
    void shouldReturnInternalServerErrorWhenServiceThrowsUnexpectedException() throws Exception {
        // Given
        UUID workerId = UUID.randomUUID();
        doThrow(new RuntimeException("Database connection failed"))
                .when(workerRegistryService).approveWorker(workerId);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("An unexpected internal server error occurred."));

        verify(workerRegistryService).approveWorker(workerId);
    }

    @Test
    @DisplayName("Should handle multiple sequential approve requests independently")
    void shouldHandleMultipleApprovalRequestsIndependently() throws Exception {
        // Given
        UUID workerId1 = UUID.randomUUID();
        UUID workerId2 = UUID.randomUUID();
        doReturn("raw-api-key-1").when(workerRegistryService).approveWorker(workerId1);
        doReturn("raw-api-key-2").when(workerRegistryService).approveWorker(workerId2);

        // When + Then
        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId1)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/workers/{workerId}/approve", workerId2)
                        .with(user(ADMIN_USER).roles(ADMIN_ROLE))
                        .with(csrf()))
                .andExpect(status().isOk());

        verify(workerRegistryService).approveWorker(workerId1);
        verify(workerRegistryService).approveWorker(workerId2);
    }
}
