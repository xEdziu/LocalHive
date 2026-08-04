package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;

record ResearchProtocolCombination(
        ResearchOperation operation,
        ResearchDataTransferMode dataTransferMode,
        ResearchPayloadFormat payloadFormat
) {
}
