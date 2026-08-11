package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEventType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenarioStatus;
import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ResearchBenchmarkDatasetDto(
        String schemaVersion,
        LocalDateTime generatedAt,
        BenchmarkRunDto benchmarkRun,
        List<ScenarioDto> scenarios,
        List<MeasurementDto> measurements,
        List<EventDto> events,
        SummaryDto summary
) {

    public ResearchBenchmarkDatasetDto {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        measurements = measurements == null ? List.of() : List.copyOf(measurements);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public record BenchmarkRunDto(
            UUID benchmarkRunId,
            String displayName,
            String description,
            BenchmarkRunStatus status,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            List<String> tags,
            String notes
    ) {

        public BenchmarkRunDto {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record ScenarioDto(
            UUID scenarioId,
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

    public record MeasurementDto(
            UUID measurementId,
            UUID benchmarkRunId,
            UUID scenarioId,
            BenchmarkMeasurementType type,
            BigDecimal valueNumeric,
            String unit,
            LocalDateTime recordedAt,
            String notes
    ) {
    }

    public record EventDto(
            UUID eventId,
            UUID benchmarkRunId,
            UUID scenarioId,
            BenchmarkEventType type,
            LocalDateTime occurredAt,
            String message,
            String metadataJson,
            UUID relatedExecutionId,
            UUID relatedExecutionGroupId
    ) {
    }

    public record SummaryDto(
            long scenarioCount,
            long measurementCount,
            long eventCount,
            List<ProtocolSummaryDto> protocols,
            List<OperationSummaryDto> operations,
            List<WorkloadSummaryDto> workloads
    ) {

        public SummaryDto {
            protocols = protocols == null ? List.of() : List.copyOf(protocols);
            operations = operations == null ? List.of() : List.copyOf(operations);
            workloads = workloads == null ? List.of() : List.copyOf(workloads);
        }
    }

    public record ProtocolSummaryDto(
            ResearchProtocol protocol,
            long scenarioCount,
            long completedScenarioCount,
            long failedScenarioCount,
            long skippedScenarioCount,
            BigDecimal avgRequestLatencyMs,
            BigDecimal avgPayloadResponseBytes,
            BigDecimal errorCount
    ) {
    }

    public record OperationSummaryDto(
            ResearchOperation operation,
            long scenarioCount,
            long completedScenarioCount,
            long failedScenarioCount,
            long skippedScenarioCount,
            BigDecimal avgRequestLatencyMs,
            BigDecimal avgPayloadResponseBytes,
            BigDecimal errorCount
    ) {
    }

    public record WorkloadSummaryDto(
            String workloadId,
            long scenarioCount,
            long completedScenarioCount,
            long failedScenarioCount,
            long skippedScenarioCount,
            BigDecimal avgRequestLatencyMs,
            BigDecimal avgPayloadResponseBytes,
            BigDecimal errorCount
    ) {
    }
}
