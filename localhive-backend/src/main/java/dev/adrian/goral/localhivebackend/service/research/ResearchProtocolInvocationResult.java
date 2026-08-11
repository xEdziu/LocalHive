package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

public record ResearchProtocolInvocationResult(
        ResearchProtocol protocol,
        ResearchOperation operation,
        long requestLatencyMs,
        long payloadResponseBytes
) {
}
