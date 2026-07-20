package dev.adrian.goral.localhivebackend.dto;

import java.util.Map;
import java.util.UUID;

public record AdminCreateExecutionRequestDto(
        UUID workDefinitionVersionId,
        UUID workerId,
        String assignmentMode,
        String displayName,
        Map<String, Object> configuration
) {
}
