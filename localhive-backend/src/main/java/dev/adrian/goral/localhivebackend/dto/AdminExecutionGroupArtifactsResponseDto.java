package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdminExecutionGroupArtifactsResponseDto(
        UUID executionGroupId,
        String displayName,
        String status,
        String mergeMode,
        String failurePolicy,
        AdminExecutionGroupArtifactSummaryResponseDto artifactSummary,
        List<ShardArtifactsResponseDto> shards,
        MergeArtifactsResponseDto merge,
        List<GroupArtifactResponseDto> preferredOutputs
) {

    public AdminExecutionGroupArtifactsResponseDto {
        artifactSummary = Objects.requireNonNull(artifactSummary, "artifactSummary must not be null.");
        shards = shards == null ? List.of() : List.copyOf(shards);
        merge = Objects.requireNonNull(merge, "merge must not be null.");
        preferredOutputs = preferredOutputs == null ? List.of() : List.copyOf(preferredOutputs);
    }

    public record ShardArtifactsResponseDto(
            Integer shardIndex,
            Integer shardCount,
            UUID executionId,
            String executionStatus,
            UUID workerId,
            String workerHostname,
            long artifactCount,
            List<GroupArtifactResponseDto> artifacts
    ) {

        public ShardArtifactsResponseDto {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    public record MergeArtifactsResponseDto(
            boolean exists,
            int mergeExecutionCount,
            UUID executionId,
            String executionStatus,
            UUID workerId,
            String workerHostname,
            long artifactCount,
            List<GroupArtifactResponseDto> artifacts
    ) {

        public MergeArtifactsResponseDto {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    public record GroupArtifactResponseDto(
            UUID artifactId,
            UUID executionId,
            String groupRole,
            Integer shardIndex,
            String relativePath,
            String originalFilename,
            String contentType,
            long sizeBytes,
            LocalDateTime createdAt
    ) {
    }
}
