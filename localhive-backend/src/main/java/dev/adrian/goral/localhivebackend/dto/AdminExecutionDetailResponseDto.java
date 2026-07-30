package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionDetailResponseDto(
        UUID executionId,
        String displayName,
        String status,
        String executorId,
        int executorContractVersion,
        GroupMetadataDto groupMetadata,
        WorkDefinitionDto workDefinition,
        WorkInstanceDto workInstance,
        AssignmentDto assignment,
        TimingDto timing,
        ArtifactsDto artifacts,
        FailureDto failure
) {

    public record WorkDefinitionDto(
            UUID definitionId,
            UUID definitionVersionId,
            String logicalId,
            int version,
            String name
    ) {
    }

    public record WorkInstanceDto(
            UUID instanceId,
            String displayName
    ) {
    }

    public record GroupMetadataDto(
            UUID executionGroupId,
            String groupRole,
            Integer shardIndex,
            Integer shardCount
    ) {
    }

    public record AssignmentDto(
            UUID assignmentId,
            UUID workerId,
            String workerHostname,
            String mode,
            LocalDateTime assignedAt,
            LocalDateTime claimedAt
    ) {
    }

    public record TimingDto(
            LocalDateTime createdAt,
            LocalDateTime queuedAt,
            LocalDateTime assignedAt,
            LocalDateTime claimedAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LocalDateTime cancelledAt,
            LocalDateTime expiredAt,
            Long durationMs
    ) {
    }

    public record ArtifactsDto(
            long outputArtifactCount
    ) {
    }

    public record FailureDto(
            String code,
            String message
    ) {
    }
}
