package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocolValidationReasonCode;

public record AdminResearchProtocolValidationResponseDto(
        boolean valid,
        ResearchProtocolValidationReasonCode reasonCode,
        String reasonMessage
) {
}
