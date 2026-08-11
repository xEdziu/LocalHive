package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenarioStatus;
import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBenchmarkScenarioResponseDto(
        UUID benchmarkScenarioId,
        UUID benchmarkRunId,
        int scenarioIndex,
        String displayName,
        String workloadId,
        ResearchProtocol protocol,
        ResearchOperation operation,
        ResearchDataTransferMode dataTransferMode,
        ResearchPayloadFormat payloadFormat,
        BenchmarkScenarioStatus status,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        UUID executionId,
        UUID executionGroupId,
        String errorCode,
        String errorMessage,
        String notes
) {
}
