package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminResearchFaultScenarioCatalogResponseDto(
        LocalDateTime generatedAt,
        List<AdminResearchFaultScenarioDescriptorResponseDto> scenarios
) {

    public AdminResearchFaultScenarioCatalogResponseDto {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }
}
