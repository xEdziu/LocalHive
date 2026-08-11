package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchWorkloadDataProfile {
    INLINE_ONLY,
    WORKSPACE_ARTIFACT_REQUIRED,
    OUTPUT_ARTIFACTS_EXPECTED,
    SHARDED_OUTPUTS_EXPECTED,
    MERGE_OUTPUTS_EXPECTED
}
