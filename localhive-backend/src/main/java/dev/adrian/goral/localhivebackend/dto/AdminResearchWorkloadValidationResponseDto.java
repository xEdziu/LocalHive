package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadValidationReasonCode;

public record AdminResearchWorkloadValidationResponseDto(
        boolean valid,
        ResearchWorkloadValidationReasonCode reasonCode,
        String reasonMessage
) {
}
