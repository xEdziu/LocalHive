package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerResponseDto {
    private UUID id;
    private String hostname;
    private String ipAddress;
    private String osType;
    private Integer totalRamMb;
    private Integer sharedRamMb;
    private Integer cpuCores;
    private String gpuName;
    private WorkerApprovalStatus approvalStatus;
    private WorkerConnectionStatus connectionStatus;
    private WorkerAvailabilityStatus availabilityStatus;
    private WorkerStatus status;
    private LocalDateTime lastHeartbeatAt;

    public static WorkerResponseDto fromEntity(Worker worker) {
        if (worker == null) return null;
        return WorkerResponseDto.builder()
                .id(worker.getId())
                .hostname(worker.getHostname())
                .ipAddress(worker.getIpAddress())
                .osType(worker.getOsType())
                .totalRamMb(worker.getTotalRamMb())
                .sharedRamMb(worker.getSharedRamMb())
                .cpuCores(worker.getCpuCores())
                .gpuName(worker.getGpuName())
                .approvalStatus(worker.getApprovalStatus())
                .connectionStatus(worker.getConnectionStatus())
                .availabilityStatus(worker.getAvailabilityStatus())
                .status(toLegacyStatus(worker))
                .lastHeartbeatAt(worker.getLastHeartbeatAt())
                .build();
    }

    private static WorkerStatus toLegacyStatus(Worker worker) {
        WorkerApprovalStatus approvalStatus = Objects.requireNonNull(
                worker.getApprovalStatus(),
                "approvalStatus must not be null"
        );
        WorkerConnectionStatus connectionStatus = Objects.requireNonNull(
                worker.getConnectionStatus(),
                "connectionStatus must not be null"
        );
        WorkerAvailabilityStatus availabilityStatus = Objects.requireNonNull(
                worker.getAvailabilityStatus(),
                "availabilityStatus must not be null"
        );

        if (approvalStatus == WorkerApprovalStatus.PENDING) {
            return WorkerStatus.PENDING;
        }
        if (connectionStatus == WorkerConnectionStatus.OFFLINE) {
            return WorkerStatus.OFFLINE;
        }
        if (availabilityStatus == WorkerAvailabilityStatus.PAUSED) {
            return WorkerStatus.PAUSED;
        }
        return WorkerStatus.ACTIVE;
    }
}
