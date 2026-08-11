package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminResearchWorkloadCatalogResponseDto(
        LocalDateTime generatedAt,
        List<AdminResearchWorkloadDescriptorResponseDto> workloads
) {

    public AdminResearchWorkloadCatalogResponseDto {
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
    }
}
