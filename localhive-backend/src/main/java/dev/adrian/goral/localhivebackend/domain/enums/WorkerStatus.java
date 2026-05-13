package dev.adrian.goral.localhivebackend.domain.enums;

public enum WorkerStatus {
    PENDING,  // Has requested, waiting for approval
    ACTIVE,   // Accepted, sends heartbeat
    OFFLINE   // No contact (e.g. PC turned off)
}