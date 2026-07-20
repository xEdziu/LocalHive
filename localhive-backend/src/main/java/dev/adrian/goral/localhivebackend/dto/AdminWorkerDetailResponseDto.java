package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminWorkerDetailResponseDto(
        UUID workerId,
        String hostname,
        String ipAddress,
        String osType,
        HardwareDto hardware,
        StatusDto status,
        HeartbeatDto heartbeat,
        CapabilitiesDto capabilities,
        ExecutionSummaryDto currentExecution,
        ExecutionSummaryDto lastExecution,
        List<ExecutionSummaryDto> recentExecutions
) {

    public AdminWorkerDetailResponseDto {
        recentExecutions = recentExecutions == null ? List.of() : List.copyOf(recentExecutions);
    }

    public record HardwareDto(
            Integer totalRamMb,
            Integer sharedRamMb,
            Integer cpuCores,
            String gpuName
    ) {
    }

    public record StatusDto(
            WorkerApprovalStatus approval,
            WorkerConnectionStatus connection,
            WorkerAvailabilityStatus availability
    ) {
    }

    public record HeartbeatDto(
            LocalDateTime lastSeenAt,
            LocalDateTime lastHeartbeatAt,
            boolean pauseEnabled
    ) {
    }

    public record CapabilitiesDto(
            LocalDateTime reportedAt,
            List<ExecutorCapabilityDto> executors,
            DockerCapabilityDto docker
    ) {
        public CapabilitiesDto {
            executors = executors == null ? List.of() : List.copyOf(executors);
        }
    }

    public record ExecutorCapabilityDto(
            String executorId,
            int executorContractVersion,
            boolean enabled
    ) {
    }

    public record DockerCapabilityDto(
            Boolean enabled,
            List<String> allowedImages,
            Integer maxMemoryMb,
            Integer maxCpuCores,
            Boolean gpuAllowed
    ) {
        public DockerCapabilityDto {
            allowedImages = allowedImages == null ? null : List.copyOf(allowedImages);
        }
    }

    public record ExecutionSummaryDto(
            UUID executionId,
            String displayName,
            String status,
            String executorId,
            int executorContractVersion,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            Long durationMs,
            long outputArtifactCount
    ) {
    }
}
