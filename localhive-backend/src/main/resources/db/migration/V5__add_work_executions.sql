CREATE TABLE work_executions (
    id UUID NOT NULL,
    definition_version_id UUID NOT NULL,
    instance_id UUID,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    queued_at TIMESTAMP(6) NOT NULL,
    assigned_at TIMESTAMP(6),
    claimed_at TIMESTAMP(6),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    cancelled_at TIMESTAMP(6),
    expired_at TIMESTAMP(6),
    resolved_configuration_snapshot JSONB NOT NULL,
    resolved_required_ram_mb INTEGER NOT NULL,
    resolved_required_cpu_cores INTEGER NOT NULL,
    resolved_gpu_required BOOLEAN NOT NULL,
    failure_code VARCHAR(255),
    failure_message TEXT,
    CONSTRAINT work_executions_pkey PRIMARY KEY (id),
    CONSTRAINT fk_work_executions_definition_version_id FOREIGN KEY (definition_version_id) REFERENCES work_definition_versions(id),
    CONSTRAINT fk_work_executions_instance_id FOREIGN KEY (instance_id) REFERENCES work_instances(id),
    CONSTRAINT work_executions_status_check CHECK (status IN ('QUEUED', 'ASSIGNED', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT work_executions_resolved_configuration_snapshot_check CHECK (jsonb_typeof(resolved_configuration_snapshot) = 'object'),
    CONSTRAINT work_executions_resolved_required_ram_mb_check CHECK (resolved_required_ram_mb >= 0),
    CONSTRAINT work_executions_resolved_required_cpu_cores_check CHECK (resolved_required_cpu_cores >= 0),
    CONSTRAINT work_executions_lifecycle_timestamp_check CHECK (
        (status <> 'ASSIGNED' OR assigned_at IS NOT NULL)
        AND (status <> 'CLAIMED' OR claimed_at IS NOT NULL)
        AND (status <> 'RUNNING' OR started_at IS NOT NULL)
        AND (status <> 'SUCCEEDED' OR completed_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND completed_at IS NULL))
        AND (status <> 'EXPIRED' OR (expired_at IS NOT NULL AND completed_at IS NULL))
    ),
    CONSTRAINT work_executions_failure_fields_check CHECK (
        (
            status = 'FAILED'
            AND completed_at IS NOT NULL
            AND failure_code IS NOT NULL
            AND length(btrim(failure_code)) > 0
        )
        OR
        (
            status <> 'FAILED'
            AND failure_code IS NULL
            AND failure_message IS NULL
        )
    )
);
