package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchWorkloadType {
    NO_OP,
    SMALL_JSON,
    FILE_INPUT_OUTPUT,
    SHARDED_OPTIMIZATION,
    AGENT_MERGE,
    LONG_RUNNING,
    FAILING_TASK,
    CANCELLED_GROUP,
    MANY_SMALL_JOBS,
    FEW_HEAVY_JOBS
}
