package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchFaultScenarioType {
    WORKER_OFFLINE,
    TASK_FAILURE,
    MERGE_FAILURE,
    GROUP_CANCELLED,
    INVALID_PAYLOAD,
    MALFORMED_SOAP,
    BROKEN_WEBSOCKET_STREAM,
    UNSUPPORTED_PROTOCOL_COMBINATION,
    TIMEOUT
}
