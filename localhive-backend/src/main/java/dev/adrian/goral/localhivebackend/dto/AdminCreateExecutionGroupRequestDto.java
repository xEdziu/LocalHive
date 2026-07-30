package dev.adrian.goral.localhivebackend.dto;

import java.util.Map;
import java.util.UUID;

public record AdminCreateExecutionGroupRequestDto(
        String displayName,
        UUID workDefinitionVersionId,
        Integer shardCount,
        String mergeMode,
        String failurePolicy,
        String assignmentMode,
        UUID workerId,
        Map<String, Object> configurationTemplate
) {
}
