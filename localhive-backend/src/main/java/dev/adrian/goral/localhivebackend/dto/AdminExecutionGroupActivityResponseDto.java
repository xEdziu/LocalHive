package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminExecutionGroupActivityResponseDto(
        UUID executionGroupId,
        String displayName,
        String status,
        String mergeMode,
        String failurePolicy,
        LocalDateTime generatedAt,
        List<ActivityEventResponseDto> events
) {

    public AdminExecutionGroupActivityResponseDto {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public record ActivityEventResponseDto(
            AdminExecutionGroupActivityEventType type,
            LocalDateTime occurredAt,
            String message,
            UUID executionId,
            String groupRole,
            Integer shardIndex,
            UUID workerId,
            String workerHostname,
            UUID artifactId,
            String relativePath,
            String status
    ) {
    }
}
