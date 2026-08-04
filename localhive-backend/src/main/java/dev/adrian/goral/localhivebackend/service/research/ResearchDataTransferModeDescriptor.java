package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;

record ResearchDataTransferModeDescriptor(
        ResearchDataTransferMode mode,
        String description
) {
}
