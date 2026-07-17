CREATE TABLE execution_assignments (
    id UUID NOT NULL,
    execution_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    assignment_mode VARCHAR(255) NOT NULL,
    assigned_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT execution_assignments_pkey PRIMARY KEY (id),
    CONSTRAINT uk_execution_assignments_execution_id UNIQUE (execution_id),
    CONSTRAINT fk_execution_assignments_execution_id FOREIGN KEY (execution_id) REFERENCES work_executions(id),
    CONSTRAINT fk_execution_assignments_worker_id FOREIGN KEY (worker_id) REFERENCES workers(id),
    CONSTRAINT execution_assignments_assignment_mode_check CHECK (assignment_mode IN ('AUTO', 'PREFER', 'REQUIRE'))
);

CREATE INDEX idx_execution_assignments_worker_id ON execution_assignments(worker_id);

CREATE TABLE execution_attempts (
    id UUID NOT NULL,
    execution_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(255) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    failure_code VARCHAR(255),
    failure_message TEXT,
    CONSTRAINT execution_attempts_pkey PRIMARY KEY (id),
    CONSTRAINT uk_execution_attempts_execution_id UNIQUE (execution_id),
    CONSTRAINT uk_execution_attempts_execution_attempt_number UNIQUE (execution_id, attempt_number),
    CONSTRAINT fk_execution_attempts_execution_id FOREIGN KEY (execution_id) REFERENCES work_executions(id),
    CONSTRAINT fk_execution_attempts_assignment_id FOREIGN KEY (assignment_id) REFERENCES execution_assignments(id),
    CONSTRAINT execution_attempts_attempt_number_check CHECK (attempt_number = 1),
    CONSTRAINT execution_attempts_status_check CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT execution_attempts_lifecycle_check CHECK (
        (
            status = 'RUNNING'
            AND completed_at IS NULL
            AND failure_code IS NULL
            AND failure_message IS NULL
        )
        OR
        (
            status = 'SUCCEEDED'
            AND completed_at IS NOT NULL
            AND failure_code IS NULL
            AND failure_message IS NULL
        )
        OR
        (
            status = 'FAILED'
            AND completed_at IS NOT NULL
            AND failure_code IS NOT NULL
            AND length(btrim(failure_code)) > 0
            AND (failure_message IS NULL OR length(btrim(failure_message)) > 0)
        )
        OR
        (
            status = 'CANCELLED'
            AND completed_at IS NOT NULL
            AND failure_code IS NULL
            AND failure_message IS NULL
        )
    )
);
