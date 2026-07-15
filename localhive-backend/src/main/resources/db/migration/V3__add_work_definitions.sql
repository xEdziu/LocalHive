CREATE TABLE work_definitions (
    id UUID NOT NULL,
    logical_identifier VARCHAR(255) NOT NULL,
    work_type VARCHAR(255) NOT NULL,
    source_type VARCHAR(255) NOT NULL,
    original_definition_id VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT work_definitions_pkey PRIMARY KEY (id),
    CONSTRAINT uk_work_definitions_logical_identifier UNIQUE (logical_identifier),
    CONSTRAINT work_definitions_logical_identifier_check CHECK (logical_identifier ~ '^[a-z0-9]+(-[a-z0-9]+)*(\.[a-z0-9]+(-[a-z0-9]+)*)+$'),
    CONSTRAINT work_definitions_work_type_check CHECK (work_type IN ('TASK', 'WORKLOAD')),
    CONSTRAINT work_definitions_source_type_check CHECK (source_type IN ('LOCAL', 'IMPORTED'))
);

CREATE TABLE work_definition_versions (
    id UUID NOT NULL,
    definition_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    executor_id VARCHAR(255) NOT NULL,
    executor_contract_version INTEGER NOT NULL,
    executor_configuration JSONB NOT NULL,
    content_checksum VARCHAR(64) NOT NULL,
    approval_status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by_user_id UUID NOT NULL,
    imported_at TIMESTAMP(6),
    reviewed_at TIMESTAMP(6),
    reviewed_by_user_id UUID,
    CONSTRAINT work_definition_versions_pkey PRIMARY KEY (id),
    CONSTRAINT uk_work_definition_versions_definition_version UNIQUE (definition_id, version_number),
    CONSTRAINT fk_work_definition_versions_definition_id FOREIGN KEY (definition_id) REFERENCES work_definitions(id),
    CONSTRAINT fk_work_definition_versions_created_by_user_id FOREIGN KEY (created_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_work_definition_versions_reviewed_by_user_id FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id),
    CONSTRAINT work_definition_versions_version_number_check CHECK (version_number >= 1),
    CONSTRAINT work_definition_versions_executor_contract_version_check CHECK (executor_contract_version >= 1),
    CONSTRAINT work_definition_versions_executor_configuration_check CHECK (jsonb_typeof(executor_configuration) = 'object'),
    CONSTRAINT work_definition_versions_content_checksum_check CHECK (content_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT work_definition_versions_approval_status_check CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT work_definition_versions_review_metadata_check CHECK (
        (
            approval_status = 'PENDING'
            AND reviewed_at IS NULL
            AND reviewed_by_user_id IS NULL
        )
        OR
        (
            approval_status IN ('APPROVED', 'REJECTED')
            AND reviewed_at IS NOT NULL
            AND reviewed_by_user_id IS NOT NULL
        )
    )
);
