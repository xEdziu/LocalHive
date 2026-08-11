CREATE TABLE benchmark_runs (
    benchmark_run_id UUID NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    created_by VARCHAR(255),
    tags JSONB,
    notes TEXT,
    CONSTRAINT benchmark_runs_pkey PRIMARY KEY (benchmark_run_id),
    CONSTRAINT benchmark_runs_display_name_check CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT benchmark_runs_status_check CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT benchmark_runs_description_check CHECK (description IS NULL OR length(description) <= 2000),
    CONSTRAINT benchmark_runs_notes_check CHECK (notes IS NULL OR length(notes) <= 4000),
    CONSTRAINT benchmark_runs_tags_array_check CHECK (tags IS NULL OR jsonb_typeof(tags) = 'array')
);

CREATE INDEX idx_benchmark_runs_status ON benchmark_runs(status);
CREATE INDEX idx_benchmark_runs_created_at ON benchmark_runs(created_at);

CREATE TABLE benchmark_scenarios (
    benchmark_scenario_id UUID NOT NULL,
    benchmark_run_id UUID NOT NULL,
    scenario_index INTEGER NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    workload_id VARCHAR(100) NOT NULL,
    protocol VARCHAR(50) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    data_transfer_mode VARCHAR(100) NOT NULL,
    payload_format VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    execution_id UUID,
    execution_group_id UUID,
    error_code VARCHAR(100),
    error_message TEXT,
    notes TEXT,
    CONSTRAINT benchmark_scenarios_pkey PRIMARY KEY (benchmark_scenario_id),
    CONSTRAINT fk_benchmark_scenarios_run_id FOREIGN KEY (benchmark_run_id) REFERENCES benchmark_runs(benchmark_run_id) ON DELETE CASCADE,
    CONSTRAINT benchmark_scenarios_run_index_unique UNIQUE (benchmark_run_id, scenario_index),
    CONSTRAINT benchmark_scenarios_scenario_index_check CHECK (scenario_index >= 0),
    CONSTRAINT benchmark_scenarios_display_name_check CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT benchmark_scenarios_workload_id_check CHECK (length(btrim(workload_id)) > 0),
    CONSTRAINT benchmark_scenarios_protocol_check CHECK (protocol IN ('REST', 'WEBSOCKET', 'SOAP')),
    CONSTRAINT benchmark_scenarios_operation_check CHECK (operation IN ('CREATE_SINGLE_EXECUTION', 'CREATE_EXECUTION_GROUP', 'GET_EXECUTION_STATUS', 'GET_GROUP_DETAIL', 'GET_GROUP_ACTIVITY', 'GET_GROUP_ARTIFACTS', 'STREAM_GROUP_ACTIVITY', 'STOP_STREAM_GROUP_ACTIVITY', 'DOWNLOAD_ARTIFACT', 'CANCEL_GROUP', 'RECONCILE_GROUP')),
    CONSTRAINT benchmark_scenarios_data_transfer_mode_check CHECK (data_transfer_mode IN ('INLINE_JSON', 'INLINE_XML', 'WORKSPACE_ARTIFACT', 'OUTPUT_ARTIFACT', 'STREAMED_EVENTS')),
    CONSTRAINT benchmark_scenarios_payload_format_check CHECK (payload_format IN ('JSON', 'XML', 'BINARY', 'MULTIPART')),
    CONSTRAINT benchmark_scenarios_status_check CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT benchmark_scenarios_error_fields_check CHECK (
        (error_code IS NULL OR length(btrim(error_code)) > 0)
        AND (error_message IS NULL OR length(btrim(error_message)) > 0)
    ),
    CONSTRAINT benchmark_scenarios_notes_check CHECK (notes IS NULL OR length(notes) <= 4000)
);

CREATE INDEX idx_benchmark_scenarios_run_id ON benchmark_scenarios(benchmark_run_id);
CREATE INDEX idx_benchmark_scenarios_workload_id ON benchmark_scenarios(workload_id);
CREATE INDEX idx_benchmark_scenarios_protocol ON benchmark_scenarios(protocol);
CREATE INDEX idx_benchmark_scenarios_status ON benchmark_scenarios(status);
CREATE INDEX idx_benchmark_scenarios_execution_id ON benchmark_scenarios(execution_id);
CREATE INDEX idx_benchmark_scenarios_execution_group_id ON benchmark_scenarios(execution_group_id);

CREATE TABLE benchmark_measurements (
    benchmark_measurement_id UUID NOT NULL,
    benchmark_run_id UUID NOT NULL,
    benchmark_scenario_id UUID,
    type VARCHAR(100) NOT NULL,
    value_numeric NUMERIC(19, 4) NOT NULL,
    unit VARCHAR(30) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    notes TEXT,
    CONSTRAINT benchmark_measurements_pkey PRIMARY KEY (benchmark_measurement_id),
    CONSTRAINT fk_benchmark_measurements_run_id FOREIGN KEY (benchmark_run_id) REFERENCES benchmark_runs(benchmark_run_id) ON DELETE CASCADE,
    CONSTRAINT fk_benchmark_measurements_scenario_id FOREIGN KEY (benchmark_scenario_id) REFERENCES benchmark_scenarios(benchmark_scenario_id) ON DELETE CASCADE,
    CONSTRAINT benchmark_measurements_type_check CHECK (type IN ('REQUEST_LATENCY_MS', 'END_TO_END_TIME_MS', 'TIME_TO_FIRST_STATUS_MS', 'TIME_TO_FINAL_STATUS_MS', 'PAYLOAD_REQUEST_BYTES', 'PAYLOAD_RESPONSE_BYTES', 'SERIALIZATION_TIME_MS', 'DESERIALIZATION_TIME_MS', 'ARTIFACT_UPLOAD_TIME_MS', 'ARTIFACT_DOWNLOAD_TIME_MS', 'ROUND_TRIP_COUNT', 'ERROR_COUNT', 'RETRY_COUNT', 'THROUGHPUT_JOBS_PER_MINUTE')),
    CONSTRAINT benchmark_measurements_value_check CHECK (value_numeric >= 0),
    CONSTRAINT benchmark_measurements_unit_check CHECK (length(btrim(unit)) > 0),
    CONSTRAINT benchmark_measurements_notes_check CHECK (notes IS NULL OR length(notes) <= 1000)
);

CREATE INDEX idx_benchmark_measurements_run_id ON benchmark_measurements(benchmark_run_id);
CREATE INDEX idx_benchmark_measurements_scenario_id ON benchmark_measurements(benchmark_scenario_id);
CREATE INDEX idx_benchmark_measurements_type ON benchmark_measurements(type);

CREATE TABLE benchmark_events (
    benchmark_event_id UUID NOT NULL,
    benchmark_run_id UUID NOT NULL,
    benchmark_scenario_id UUID,
    type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    message TEXT NOT NULL,
    metadata_json TEXT,
    related_execution_id UUID,
    related_execution_group_id UUID,
    CONSTRAINT benchmark_events_pkey PRIMARY KEY (benchmark_event_id),
    CONSTRAINT fk_benchmark_events_run_id FOREIGN KEY (benchmark_run_id) REFERENCES benchmark_runs(benchmark_run_id) ON DELETE CASCADE,
    CONSTRAINT fk_benchmark_events_scenario_id FOREIGN KEY (benchmark_scenario_id) REFERENCES benchmark_scenarios(benchmark_scenario_id) ON DELETE CASCADE,
    CONSTRAINT benchmark_events_type_check CHECK (type IN ('RUN_CREATED', 'RUN_STARTED', 'RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'SCENARIO_CREATED', 'SCENARIO_STARTED', 'SCENARIO_COMPLETED', 'SCENARIO_FAILED', 'SCENARIO_SKIPPED', 'MEASUREMENT_RECORDED', 'NOTE_RECORDED')),
    CONSTRAINT benchmark_events_message_check CHECK (length(btrim(message)) > 0 AND length(message) <= 2000),
    CONSTRAINT benchmark_events_metadata_json_check CHECK (metadata_json IS NULL OR length(metadata_json) <= 8000)
);

CREATE INDEX idx_benchmark_events_run_id ON benchmark_events(benchmark_run_id);
CREATE INDEX idx_benchmark_events_scenario_id ON benchmark_events(benchmark_scenario_id);
CREATE INDEX idx_benchmark_events_occurred_at ON benchmark_events(occurred_at);
CREATE INDEX idx_benchmark_events_type ON benchmark_events(type);
