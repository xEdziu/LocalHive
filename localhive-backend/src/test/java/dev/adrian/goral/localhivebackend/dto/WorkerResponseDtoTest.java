package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerResponseDtoTest {

    @Test
    @DisplayName("Legacy status gives pending approval precedence")
    void shouldMapPendingApprovalToLegacyPending() {
        WorkerResponseDto dto = WorkerResponseDto.fromEntity(worker(
                WorkerApprovalStatus.PENDING,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.PAUSED
        ));

        assertThat(dto.getStatus()).isEqualTo(WorkerStatus.PENDING);
    }

    @Test
    @DisplayName("Legacy status gives offline connection precedence over paused availability")
    void shouldMapOfflineConnectionToLegacyOfflineBeforePaused() {
        WorkerResponseDto dto = WorkerResponseDto.fromEntity(worker(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.OFFLINE,
                WorkerAvailabilityStatus.PAUSED
        ));

        assertThat(dto.getStatus()).isEqualTo(WorkerStatus.OFFLINE);
    }

    @Test
    @DisplayName("Legacy status maps online paused worker to paused")
    void shouldMapOnlinePausedWorkerToLegacyPaused() {
        WorkerResponseDto dto = WorkerResponseDto.fromEntity(worker(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.PAUSED
        ));

        assertThat(dto.getStatus()).isEqualTo(WorkerStatus.PAUSED);
    }

    @Test
    @DisplayName("Legacy status maps approved online available worker to active")
    void shouldMapApprovedOnlineAvailableWorkerToLegacyActive() {
        WorkerResponseDto dto = WorkerResponseDto.fromEntity(worker(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        ));

        assertThat(dto.getStatus()).isEqualTo(WorkerStatus.ACTIVE);
    }

    @Test
    @DisplayName("Legacy status fails fast when approval status is null")
    void shouldFailFastWhenApprovalStatusIsNull() {
        NullPointerException exception = assertThrows(NullPointerException.class, () ->
                WorkerResponseDto.fromEntity(worker(
                        null,
                        WorkerConnectionStatus.ONLINE,
                        WorkerAvailabilityStatus.AVAILABLE
                ))
        );

        assertThat(exception).hasMessage("approvalStatus must not be null");
    }

    @Test
    @DisplayName("Legacy status fails fast when connection status is null")
    void shouldFailFastWhenConnectionStatusIsNull() {
        NullPointerException exception = assertThrows(NullPointerException.class, () ->
                WorkerResponseDto.fromEntity(worker(
                        WorkerApprovalStatus.APPROVED,
                        null,
                        WorkerAvailabilityStatus.AVAILABLE
                ))
        );

        assertThat(exception).hasMessage("connectionStatus must not be null");
    }

    @Test
    @DisplayName("Legacy status fails fast when availability status is null")
    void shouldFailFastWhenAvailabilityStatusIsNull() {
        NullPointerException exception = assertThrows(NullPointerException.class, () ->
                WorkerResponseDto.fromEntity(worker(
                        WorkerApprovalStatus.APPROVED,
                        WorkerConnectionStatus.ONLINE,
                        null
                ))
        );

        assertThat(exception).hasMessage("availabilityStatus must not be null");
    }

    private static Worker worker(WorkerApprovalStatus approvalStatus,
                                 WorkerConnectionStatus connectionStatus,
                                 WorkerAvailabilityStatus availabilityStatus) {
        return Worker.builder()
                .hostname("worker-1")
                .ipAddress("192.168.1.10")
                .osType("Windows 11")
                .totalRamMb(32768)
                .sharedRamMb(4096)
                .cpuCores(16)
                .approvalStatus(approvalStatus)
                .connectionStatus(connectionStatus)
                .availabilityStatus(availabilityStatus)
                .build();
    }
}
