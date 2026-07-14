package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkerRepositoryStateSplitTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private WorkerRepository workerRepository;

    @Test
    @DisplayName("Offline monitor repository update marks active available worker offline")
    void shouldMarkApprovedOnlineAvailableWorkerOfflineWhenHeartbeatIsStale() {
        Worker worker = worker("available-worker", WorkerAvailabilityStatus.AVAILABLE);
        workerRepository.saveAndFlush(worker);

        int updated = workerRepository.markInactiveWorkersOffline(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerConnectionStatus.OFFLINE,
                LocalDateTime.now().minusMinutes(5)
        );

        Worker reloaded = workerRepository.findById(worker.getId()).orElseThrow();

        assertThat(updated).isEqualTo(1);
        assertThat(reloaded.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(reloaded.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.OFFLINE);
        assertThat(reloaded.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Offline monitor repository update preserves paused availability")
    void shouldPreservePausedAvailabilityWhenMarkingWorkerOffline() {
        Worker worker = worker("paused-worker", WorkerAvailabilityStatus.PAUSED);
        workerRepository.saveAndFlush(worker);

        int updated = workerRepository.markInactiveWorkersOffline(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerConnectionStatus.OFFLINE,
                LocalDateTime.now().minusMinutes(5)
        );

        Worker reloaded = workerRepository.findById(worker.getId()).orElseThrow();

        assertThat(updated).isEqualTo(1);
        assertThat(reloaded.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
        assertThat(reloaded.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.OFFLINE);
        assertThat(reloaded.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.PAUSED);
    }

    private static Worker worker(String hostname, WorkerAvailabilityStatus availabilityStatus) {
        return Worker.builder()
                .hostname(hostname)
                .ipAddress("192.168.1.10")
                .osType("Windows 11")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(availabilityStatus)
                .lastHeartbeatAt(LocalDateTime.now().minusMinutes(10))
                .build();
    }
}
