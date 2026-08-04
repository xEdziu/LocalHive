package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionGroupStreamHeartbeatDto(
        UUID executionGroupId,
        LocalDateTime generatedAt,
        String status
) {
}
