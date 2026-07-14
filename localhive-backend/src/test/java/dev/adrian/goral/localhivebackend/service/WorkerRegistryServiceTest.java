package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.dto.WorkerHeartbeatRequestDto;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRegistryServiceTest {

    private final WorkerRepository workerRepository = mock(WorkerRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final WorkerAuthService workerAuthService = mock(WorkerAuthService.class);
    private final WorkerRegistryService workerRegistryService = new WorkerRegistryService(
            workerRepository,
            passwordEncoder,
            workerAuthService
    );

    @Test
    @DisplayName("Registration initializes independent worker state dimensions")
    void shouldInitializeStateDimensionsWhenWorkerRegisters() {
        when(workerRepository.save(any(Worker.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Worker worker = workerRegistryService.registerNewWorker(
                "worker-1",
                "192.168.1.10",
                "Windows 11",
                32768,
                4096,
                16,
                "RTX 5080"
        );

        assertThat(worker.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.PENDING);
        assertThat(worker.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.OFFLINE);
        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Approval changes approval only")
    void shouldChangeApprovalOnlyWhenWorkerIsApproved() {
        UUID workerId = UUID.randomUUID();
        Worker worker = worker(
                workerId,
                WorkerApprovalStatus.PENDING,
                WorkerConnectionStatus.OFFLINE,
                WorkerAvailabilityStatus.PAUSED
        );

        when(workerRepository.findById(workerId)).thenReturn(Optional.of(worker));
        when(passwordEncoder.encode(any())).thenReturn("hashed-api-key");
        when(workerRepository.save(worker)).thenReturn(worker);

        String apiKey = workerRegistryService.approveWorker(workerId);

        assertThat(apiKey).isNotBlank();
        assertThat(worker.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(worker.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.OFFLINE);
        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.PAUSED);
        assertThat(worker.getApiKeyHash()).isEqualTo("hashed-api-key");
        verify(passwordEncoder).encode(apiKey);
    }

    @Test
    @DisplayName("Heartbeat active mode sets connection online and availability available")
    void shouldSetOnlineAndAvailableWhenHeartbeatReportsActiveMode() {
        UUID workerId = UUID.randomUUID();
        Worker worker = worker(
                workerId,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.PAUSED
        );
        when(workerAuthService.verifyWorker(workerId, "api-key")).thenReturn(worker);

        workerRegistryService.handleHeartbeat(workerId, "api-key", new WorkerHeartbeatRequestDto(false, 8192));

        assertThat(worker.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(worker.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.ONLINE);
        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.AVAILABLE);
        assertThat(worker.getSharedRamMb()).isEqualTo(8192);
        assertThat(worker.getLastHeartbeatAt()).isNotNull();
        verify(workerRepository).save(worker);
    }

    @Test
    @DisplayName("Heartbeat paused mode sets connection online and availability paused")
    void shouldSetOnlineAndPausedWhenHeartbeatReportsPausedMode() {
        UUID workerId = UUID.randomUUID();
        Worker worker = worker(
                workerId,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        when(workerAuthService.verifyWorker(workerId, "api-key")).thenReturn(worker);

        workerRegistryService.handleHeartbeat(workerId, "api-key", new WorkerHeartbeatRequestDto(true, 8192));

        assertThat(worker.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(worker.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.ONLINE);
        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.PAUSED);
        assertThat(worker.getSharedRamMb()).isEqualTo(8192);
        assertThat(worker.getLastHeartbeatAt()).isNotNull();
        verify(workerRepository).save(worker);
    }

    @Test
    @DisplayName("Heartbeat recovers offline paused worker to online available")
    void shouldRecoverOfflinePausedWorkerToOnlineAvailable() {
        UUID workerId = UUID.randomUUID();
        Worker worker = worker(
                workerId,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.OFFLINE,
                WorkerAvailabilityStatus.PAUSED
        );
        when(workerAuthService.verifyWorker(workerId, "api-key")).thenReturn(worker);

        workerRegistryService.handleHeartbeat(workerId, "api-key", new WorkerHeartbeatRequestDto(false, 8192));

        assertThat(worker.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(worker.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.ONLINE);
        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.AVAILABLE);
        assertThat(worker.getSharedRamMb()).isEqualTo(8192);
        assertThat(worker.getLastHeartbeatAt()).isNotNull();
        verify(workerRepository).save(worker);
    }

    @Test
    @DisplayName("Heartbeat recovers offline available worker to online paused")
    void shouldRecoverOfflineAvailableWorkerToOnlinePaused() {
        UUID workerId = UUID.randomUUID();
        Worker worker = worker(
                workerId,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.OFFLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        when(workerAuthService.verifyWorker(workerId, "api-key")).thenReturn(worker);

        workerRegistryService.handleHeartbeat(workerId, "api-key", new WorkerHeartbeatRequestDto(true, 8192));

        assertThat(worker.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(worker.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.ONLINE);
        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.PAUSED);
        assertThat(worker.getSharedRamMb()).isEqualTo(8192);
        assertThat(worker.getLastHeartbeatAt()).isNotNull();
        verify(workerRepository).save(worker);
    }

    private static Worker worker(UUID workerId,
                                 WorkerApprovalStatus approvalStatus,
                                 WorkerConnectionStatus connectionStatus,
                                 WorkerAvailabilityStatus availabilityStatus) {
        return Worker.builder()
                .id(workerId)
                .hostname("worker-" + workerId)
                .ipAddress("192.168.1.10")
                .osType("Windows 11")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(approvalStatus)
                .connectionStatus(connectionStatus)
                .availabilityStatus(availabilityStatus)
                .build();
    }
}
