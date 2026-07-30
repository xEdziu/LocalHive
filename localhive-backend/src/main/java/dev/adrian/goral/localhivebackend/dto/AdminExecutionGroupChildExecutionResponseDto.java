package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionGroupChildExecutionResponseDto(
        UUID executionId,
        String status,
        String assignmentMode,
        UUID workerId,
        String workerHostname,
        String groupRole,
        Integer shardIndex,
        Integer shardCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt
) {
}
