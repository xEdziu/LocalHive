package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminBenchmarkRunResponseDto(
        UUID benchmarkRunId,
        String displayName,
        String description,
        BenchmarkRunStatus status,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String createdBy,
        List<String> tags,
        String notes,
        long scenarioCount,
        long measurementCount,
        long eventCount
) {

    public AdminBenchmarkRunResponseDto {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
