package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultScenarioValidationReasonCode;

public record AdminResearchFaultScenarioValidationResponseDto(
        boolean valid,
        ResearchFaultScenarioValidationReasonCode reasonCode,
        String reasonMessage
) {
}
