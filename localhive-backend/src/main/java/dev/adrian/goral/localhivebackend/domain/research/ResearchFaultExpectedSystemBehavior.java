package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchFaultExpectedSystemBehavior {
    SAFE_ERROR,
    SAFE_FAILURE_STATUS,
    SAFE_CANCELLATION,
    SAFE_TIMEOUT,
    SAFE_REJECTION,
    CLEAN_DISCONNECT,
    NO_RUNTIME_MUTATION
}
