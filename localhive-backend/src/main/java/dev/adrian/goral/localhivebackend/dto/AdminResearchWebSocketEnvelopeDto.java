package dev.adrian.goral.localhivebackend.dto;

public record AdminResearchWebSocketEnvelopeDto(
        String requestId,
        String type,
        String operation,
        boolean success,
        Object data,
        AdminResearchWebSocketErrorDto error,
        String event
) {

    public static AdminResearchWebSocketEnvelopeDto response(String requestId, String operation, Object data) {
        return new AdminResearchWebSocketEnvelopeDto(
                requestId,
                "RESPONSE",
                operation,
                true,
                data,
                null,
                null
        );
    }

    public static AdminResearchWebSocketEnvelopeDto event(
            String requestId,
            String operation,
            String event,
            Object data
    ) {
        return new AdminResearchWebSocketEnvelopeDto(
                requestId,
                "EVENT",
                operation,
                true,
                data,
                null,
                event
        );
    }

    public static AdminResearchWebSocketEnvelopeDto error(
            String requestId,
            String operation,
            AdminResearchWebSocketErrorDto error
    ) {
        return new AdminResearchWebSocketEnvelopeDto(
                requestId,
                "ERROR",
                operation,
                false,
                null,
                error,
                null
        );
    }
}
