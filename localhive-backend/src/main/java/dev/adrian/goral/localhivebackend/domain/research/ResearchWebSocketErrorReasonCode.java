package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchWebSocketErrorReasonCode {
    MALFORMED_MESSAGE,
    INVALID_REQUEST_ID,
    UNKNOWN_OPERATION,
    INVALID_PAYLOAD,
    GROUP_NOT_FOUND,
    OPERATION_NOT_SUPPORTED,
    OPERATION_CONFLICT,
    UNAUTHORIZED,
    INTERNAL_ERROR
}
