package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AdminExecutionGroupDetailResponseDto(
        UUID executionGroupId,
        String displayName,
        String status,
        String mergeMode,
        String failurePolicy,
        int shardCount,
        long totalExecutions,
        long activeExecutions,
        long terminalExecutions,
        Map<String, Long> childExecutionCounts,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        String failureCode,
        String failureMessage,
        ObservabilityResponseDto observability,
        AdminExecutionGroupArtifactSummaryResponseDto artifactSummary
) {

    public AdminExecutionGroupDetailResponseDto {
        childExecutionCounts = childExecutionCounts == null ? Map.of() : Map.copyOf(childExecutionCounts);
    }

    public record ObservabilityResponseDto(
            boolean terminal,
            boolean cancelInProgress,
            boolean hasActiveChildren,
            boolean hasQueuedChildren,
            boolean canCancel,
            boolean canReconcile,
            ChildRoleCountsResponseDto shards,
            MergeObservabilityResponseDto merge
    ) {
    }

    public record ChildRoleCountsResponseDto(
            long total,
            long queued,
            long assigned,
            long claimed,
            long running,
            long succeeded,
            long failed,
            long cancelled,
            long expired,
            long terminal,
            long nonTerminal
    ) {
    }

    public record MergeObservabilityResponseDto(
            boolean exists,
            UUID executionId,
            String status,
            UUID workerId,
            String workerHostname,
            long total,
            long queued,
            long assigned,
            long claimed,
            long running,
            long succeeded,
            long failed,
            long cancelled,
            long expired,
            long terminal,
            long nonTerminal
    ) {
    }
}
