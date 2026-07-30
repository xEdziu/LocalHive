CREATE TABLE execution_groups (
    id UUID NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    merge_mode VARCHAR(255) NOT NULL,
    failure_policy VARCHAR(255) NOT NULL,
    shard_count INTEGER NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    cancelled_at TIMESTAMP(6),
    failure_code VARCHAR(255),
    failure_message TEXT,
    CONSTRAINT execution_groups_pkey PRIMARY KEY (id),
    CONSTRAINT execution_groups_display_name_check CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT execution_groups_status_check CHECK (status IN ('CREATED', 'SCHEDULING', 'RUNNING', 'MERGING', 'SUCCEEDED', 'PARTIALLY_FAILED', 'FAILED', 'CANCELLING', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT execution_groups_merge_mode_check CHECK (merge_mode IN ('NONE', 'MASTER', 'AGENT')),
    CONSTRAINT execution_groups_failure_policy_check CHECK (failure_policy IN ('FAIL_FAST', 'ALLOW_PARTIAL')),
    CONSTRAINT execution_groups_shard_count_check CHECK (shard_count > 0),
    CONSTRAINT execution_groups_failure_fields_check CHECK (
        (failure_code IS NULL OR length(btrim(failure_code)) > 0)
        AND (failure_message IS NULL OR length(btrim(failure_message)) > 0)
    )
);

CREATE INDEX idx_execution_groups_status ON execution_groups(status);
CREATE INDEX idx_execution_groups_created_at ON execution_groups(created_at);

ALTER TABLE work_executions
    ADD COLUMN execution_group_id UUID,
    ADD COLUMN group_role VARCHAR(255),
    ADD COLUMN shard_index INTEGER,
    ADD COLUMN shard_count INTEGER;

ALTER TABLE work_executions
    ADD CONSTRAINT fk_work_executions_execution_group_id FOREIGN KEY (execution_group_id) REFERENCES execution_groups(id),
    ADD CONSTRAINT work_executions_group_role_check CHECK (group_role IS NULL OR group_role IN ('SHARD', 'MERGE')),
    ADD CONSTRAINT work_executions_group_metadata_check CHECK (
        (
            execution_group_id IS NULL
            AND group_role IS NULL
            AND shard_index IS NULL
            AND shard_count IS NULL
        )
        OR
        (
            execution_group_id IS NOT NULL
            AND group_role = 'SHARD'
            AND shard_index IS NOT NULL
            AND shard_count IS NOT NULL
            AND shard_count > 0
            AND shard_index >= 0
            AND shard_index < shard_count
        )
        OR
        (
            execution_group_id IS NOT NULL
            AND group_role = 'MERGE'
            AND shard_index IS NULL
            AND (
                shard_count IS NULL
                OR shard_count > 0
            )
        )
    );

CREATE INDEX idx_work_executions_execution_group_id ON work_executions(execution_group_id);
CREATE INDEX idx_work_executions_execution_group_role_shard_index ON work_executions(execution_group_id, group_role, shard_index);
