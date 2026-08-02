CREATE TABLE execution_group_merge_plans (
    execution_group_id UUID NOT NULL,
    definition_version_id UUID NOT NULL,
    configuration_template JSONB NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT execution_group_merge_plans_pkey PRIMARY KEY (execution_group_id),
    CONSTRAINT fk_execution_group_merge_plans_execution_group_id FOREIGN KEY (execution_group_id) REFERENCES execution_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_execution_group_merge_plans_definition_version_id FOREIGN KEY (definition_version_id) REFERENCES work_definition_versions(id),
    CONSTRAINT execution_group_merge_plans_configuration_template_object_check CHECK (jsonb_typeof(configuration_template) = 'object')
);
