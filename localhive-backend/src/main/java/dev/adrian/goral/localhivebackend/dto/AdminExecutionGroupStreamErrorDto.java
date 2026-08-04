package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionGroupStreamErrorDto(
        UUID executionGroupId,
        LocalDateTime generatedAt,
        String message
) {
}
