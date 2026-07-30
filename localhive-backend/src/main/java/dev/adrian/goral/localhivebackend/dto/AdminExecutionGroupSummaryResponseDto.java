package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionGroupSummaryResponseDto(
        UUID executionGroupId,
        String displayName,
        String status,
        String mergeMode,
        String failurePolicy,
        int shardCount,
        long totalExecutions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt
) {
}
