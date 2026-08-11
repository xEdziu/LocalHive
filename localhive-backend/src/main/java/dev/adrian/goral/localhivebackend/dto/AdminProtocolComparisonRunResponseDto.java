package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AdminProtocolComparisonRunResponseDto(
        UUID benchmarkRunId,
        BenchmarkRunStatus status,
        UUID targetExecutionGroupId,
        String workloadId,
        List<ResearchProtocol> protocols,
        List<ResearchOperation> operations,
        int repetitions,
        long scenarioCount,
        long measurementCount,
        long eventCount,
        List<ProtocolSummaryDto> summary
) {

    public AdminProtocolComparisonRunResponseDto {
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
        operations = operations == null ? List.of() : List.copyOf(operations);
        summary = summary == null ? List.of() : List.copyOf(summary);
    }

    public record ProtocolSummaryDto(
            ResearchProtocol protocol,
            long completedScenarios,
            long failedScenarios,
            long skippedScenarios,
            BigDecimal avgRequestLatencyMs,
            BigDecimal avgPayloadResponseBytes,
            long errorCount
    ) {
    }
}
