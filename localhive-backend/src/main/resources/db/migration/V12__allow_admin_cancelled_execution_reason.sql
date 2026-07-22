ALTER TABLE work_executions
    DROP CONSTRAINT work_executions_lifecycle_timestamp_check;

ALTER TABLE work_executions
    ADD CONSTRAINT work_executions_lifecycle_timestamp_check CHECK (
        (status <> 'ASSIGNED' OR assigned_at IS NOT NULL)
        AND (status <> 'CLAIMED' OR claimed_at IS NOT NULL)
        AND (status <> 'RUNNING' OR started_at IS NOT NULL)
        AND (status <> 'SUCCEEDED' OR completed_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
        AND (status <> 'EXPIRED' OR (expired_at IS NOT NULL AND completed_at IS NULL))
    );

ALTER TABLE work_executions
    DROP CONSTRAINT work_executions_failure_fields_check;

ALTER TABLE work_executions
    ADD CONSTRAINT work_executions_failure_fields_check CHECK (
        (
            status = 'FAILED'
            AND completed_at IS NOT NULL
            AND failure_code IS NOT NULL
            AND length(btrim(failure_code)) > 0
        )
        OR
        (
            status = 'CANCELLED'
            AND (
                (
                    failure_code IS NULL
                    AND failure_message IS NULL
                )
                OR
                (
                    completed_at IS NOT NULL
                    AND failure_code IS NOT NULL
                    AND length(btrim(failure_code)) > 0
                    AND (
                        failure_message IS NULL
                        OR length(btrim(failure_message)) > 0
                    )
                )
            )
        )
        OR
        (
            status NOT IN ('FAILED', 'CANCELLED')
            AND failure_code IS NULL
            AND failure_message IS NULL
        )
    );
