package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEventType;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBenchmarkEventResponseDto(
        UUID benchmarkEventId,
        UUID benchmarkRunId,
        UUID benchmarkScenarioId,
        BenchmarkEventType type,
        LocalDateTime occurredAt,
        String message,
        String metadataJson,
        UUID relatedExecutionId,
        UUID relatedExecutionGroupId
) {
}
