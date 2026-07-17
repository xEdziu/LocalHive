ALTER TABLE work_definition_versions
    ADD COLUMN default_required_ram_mb INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN default_required_cpu_cores INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN default_gpu_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT work_definition_versions_default_required_ram_mb_check CHECK (default_required_ram_mb >= 0),
    ADD CONSTRAINT work_definition_versions_default_required_cpu_cores_check CHECK (default_required_cpu_cores >= 0);

CREATE TABLE work_instances (
    id UUID NOT NULL,
    definition_version_id UUID NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    configuration_overrides JSONB NOT NULL,
    override_required_ram_mb INTEGER,
    override_required_cpu_cores INTEGER,
    override_gpu_required BOOLEAN,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT work_instances_pkey PRIMARY KEY (id),
    CONSTRAINT fk_work_instances_definition_version_id FOREIGN KEY (definition_version_id) REFERENCES work_definition_versions(id),
    CONSTRAINT work_instances_display_name_check CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT work_instances_configuration_overrides_check CHECK (jsonb_typeof(configuration_overrides) = 'object'),
    CONSTRAINT work_instances_override_required_ram_mb_check CHECK (override_required_ram_mb IS NULL OR override_required_ram_mb >= 0),
    CONSTRAINT work_instances_override_required_cpu_cores_check CHECK (override_required_cpu_cores IS NULL OR override_required_cpu_cores >= 0)
);
