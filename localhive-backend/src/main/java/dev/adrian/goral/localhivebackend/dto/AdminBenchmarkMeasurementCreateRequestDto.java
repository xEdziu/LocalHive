package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminBenchmarkMeasurementCreateRequestDto(
        UUID scenarioId,
        BenchmarkMeasurementType type,
        BigDecimal valueNumeric,
        String unit,
        String notes
) {
}
