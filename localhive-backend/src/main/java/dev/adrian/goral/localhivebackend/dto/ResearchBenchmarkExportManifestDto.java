package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ResearchBenchmarkExportManifestDto(
        UUID benchmarkRunId,
        LocalDateTime generatedAt,
        List<AvailableExportDto> availableExports
) {

    public ResearchBenchmarkExportManifestDto {
        availableExports = availableExports == null ? List.of() : List.copyOf(availableExports);
    }

    public record AvailableExportDto(
            String name,
            String format,
            String path,
            String description
    ) {
    }
}
