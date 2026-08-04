package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionGroupStreamCompleteDto(
        UUID executionGroupId,
        LocalDateTime generatedAt,
        String reason
) {
}
