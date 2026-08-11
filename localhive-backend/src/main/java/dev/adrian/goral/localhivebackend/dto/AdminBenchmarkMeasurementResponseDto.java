package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBenchmarkMeasurementResponseDto(
        UUID benchmarkMeasurementId,
        UUID benchmarkRunId,
        UUID benchmarkScenarioId,
        BenchmarkMeasurementType type,
        BigDecimal valueNumeric,
        String unit,
        LocalDateTime recordedAt,
        String notes
) {
}
