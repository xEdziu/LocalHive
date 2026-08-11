package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEventType;

import java.util.UUID;

public record AdminBenchmarkEventCreateRequestDto(
        UUID scenarioId,
        BenchmarkEventType type,
        String message,
        String metadataJson,
        UUID relatedExecutionId,
        UUID relatedExecutionGroupId
) {
}
