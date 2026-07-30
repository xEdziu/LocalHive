package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionSummaryResponseDto(
        UUID executionId,
        String displayName,
        String status,
        String executorId,
        int executorContractVersion,
        String workDefinitionLogicalId,
        int workDefinitionVersion,
        UUID executionGroupId,
        String groupRole,
        Integer shardIndex,
        Integer shardCount,
        UUID workerId,
        String workerHostname,
        LocalDateTime createdAt,
        LocalDateTime assignedAt,
        LocalDateTime claimedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        long outputArtifactCount
) {
}
