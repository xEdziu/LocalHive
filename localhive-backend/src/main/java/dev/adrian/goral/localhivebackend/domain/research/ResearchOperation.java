package dev.adrian.goral.localhivebackend.domain.research;

public enum ResearchOperation {
    CREATE_SINGLE_EXECUTION,
    CREATE_EXECUTION_GROUP,
    GET_EXECUTION_STATUS,
    GET_GROUP_DETAIL,
    GET_GROUP_ACTIVITY,
    GET_GROUP_ARTIFACTS,
    STREAM_GROUP_ACTIVITY,
    DOWNLOAD_ARTIFACT,
    CANCEL_GROUP,
    RECONCILE_GROUP
}
