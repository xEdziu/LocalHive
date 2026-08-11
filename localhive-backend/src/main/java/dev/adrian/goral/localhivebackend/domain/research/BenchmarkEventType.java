package dev.adrian.goral.localhivebackend.domain.research;

public enum BenchmarkEventType {
    RUN_CREATED,
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    SCENARIO_CREATED,
    SCENARIO_STARTED,
    SCENARIO_COMPLETED,
    SCENARIO_FAILED,
    SCENARIO_SKIPPED,
    MEASUREMENT_RECORDED,
    NOTE_RECORDED
}
