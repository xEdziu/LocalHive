package dev.adrian.goral.localhivebackend.domain.enums;

public enum WorkerStatus {
    PENDING,  // Has requested, waiting for approval
    ACTIVE,   // Accepted, sends heartbeat, available for tasks
    PAUSED,   // Online but not accepting new tasks (paused state)
    OFFLINE   // No contact (e.g. PC turned off)
}