package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocolStatus;

import java.util.Set;

record ResearchProtocolDescriptor(
        ResearchProtocol protocol,
        ResearchProtocolStatus status,
        String description,
        Set<ResearchPayloadFormat> supportedPayloadFormats,
        Set<ResearchDataTransferMode> supportedDataTransferModes,
        Set<ResearchOperation> supportedOperations,
        Set<ResearchProtocolCombination> supportedCombinations
) {

    ResearchProtocolDescriptor {
        supportedPayloadFormats = Set.copyOf(supportedPayloadFormats);
        supportedDataTransferModes = Set.copyOf(supportedDataTransferModes);
        supportedOperations = Set.copyOf(supportedOperations);
        supportedCombinations = Set.copyOf(supportedCombinations);
    }
}
