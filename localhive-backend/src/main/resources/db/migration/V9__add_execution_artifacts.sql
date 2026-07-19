ALTER TABLE artifacts
    DROP CONSTRAINT artifacts_kind_check;

ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_kind_check CHECK (kind IN ('WORKSPACE_PACKAGE', 'EXECUTION_OUTPUT'));

CREATE TABLE execution_artifacts (
    id UUID NOT NULL,
    execution_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    uploaded_by_worker_id UUID NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT execution_artifacts_pkey PRIMARY KEY (id),
    CONSTRAINT uk_execution_artifacts_artifact_id UNIQUE (artifact_id),
    CONSTRAINT fk_execution_artifacts_execution_id FOREIGN KEY (execution_id) REFERENCES work_executions(id),
    CONSTRAINT fk_execution_artifacts_artifact_id FOREIGN KEY (artifact_id) REFERENCES artifacts(id),
    CONSTRAINT fk_execution_artifacts_uploaded_by_worker_id FOREIGN KEY (uploaded_by_worker_id) REFERENCES workers(id),
    CONSTRAINT execution_artifacts_relative_path_not_blank_check CHECK (length(btrim(relative_path)) > 0)
);
