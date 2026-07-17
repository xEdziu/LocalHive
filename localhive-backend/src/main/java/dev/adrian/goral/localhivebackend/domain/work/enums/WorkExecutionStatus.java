package dev.adrian.goral.localhivebackend.domain.work.enums;

public enum WorkExecutionStatus {
    QUEUED,
    ASSIGNED,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
