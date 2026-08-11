package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

public record AdminResearchWorkloadValidationRequestDto(
        String workloadId,
        ResearchProtocol protocol,
        ResearchOperation operation,
        ResearchDataTransferMode dataTransferMode,
        ResearchPayloadFormat payloadFormat
) {
}
