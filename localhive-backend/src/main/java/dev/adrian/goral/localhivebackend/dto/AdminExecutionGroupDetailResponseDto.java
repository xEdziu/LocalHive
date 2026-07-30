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
        String failureMessage
) {

    public AdminExecutionGroupDetailResponseDto {
        childExecutionCounts = childExecutionCounts == null ? Map.of() : Map.copyOf(childExecutionCounts);
    }
}
