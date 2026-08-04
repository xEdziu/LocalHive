package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;

record ResearchPayloadFormatDescriptor(
        ResearchPayloadFormat format,
        String description
) {
}
