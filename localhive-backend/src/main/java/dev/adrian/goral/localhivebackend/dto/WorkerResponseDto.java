package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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
                .status(worker.getStatus())
                .lastHeartbeatAt(worker.getLastHeartbeatAt())
                .build();
    }
}