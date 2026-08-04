package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchWebSocketErrorReasonCode;

public record AdminResearchWebSocketErrorDto(
        ResearchWebSocketErrorReasonCode reasonCode,
        String message
) {
}
