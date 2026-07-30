package dev.adrian.goral.localhivebackend.domain.work.enums;

public enum ExecutionGroupStatus {
    CREATED,
    SCHEDULING,
    RUNNING,
    MERGING,
    SUCCEEDED,
    PARTIALLY_FAILED,
    FAILED,
    CANCELLING,
    CANCELLED,
    EXPIRED
}
