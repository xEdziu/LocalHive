package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchSoapErrorReasonCode {
    MALFORMED_MESSAGE,
    UNKNOWN_OPERATION,
    INVALID_PAYLOAD,
    GROUP_NOT_FOUND,
    OPERATION_NOT_SUPPORTED,
    OPERATION_CONFLICT,
    UNAUTHORIZED,
    INTERNAL_ERROR
}
