CREATE TABLE users (
    id UUID NOT NULL,
    created_at TIMESTAMP(6),
    password_hash VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE system_settings (
    config_key VARCHAR(255) NOT NULL,
    config_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP(6),
    CONSTRAINT system_settings_pkey PRIMARY KEY (config_key)
);

CREATE TABLE workers (
    id UUID NOT NULL,
    api_key_hash VARCHAR(255),
    cpu_cores INTEGER NOT NULL,
    gpu_name VARCHAR(255),
    hostname VARCHAR(255) NOT NULL,
    ip_address VARCHAR(255) NOT NULL,
    last_heartbeat_at TIMESTAMP(6),
    os_type VARCHAR(255) NOT NULL,
    shared_ram_mb INTEGER NOT NULL,
    status VARCHAR(255) NOT NULL,
    total_ram_mb INTEGER NOT NULL,
    CONSTRAINT workers_pkey PRIMARY KEY (id),
    CONSTRAINT uk_workers_hostname UNIQUE (hostname),
    CONSTRAINT workers_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'PAUSED', 'OFFLINE'))
);

CREATE TABLE game_templates (
    id UUID NOT NULL,
    default_port INTEGER NOT NULL,
    docker_image VARCHAR(255) NOT NULL,
    min_ram_mb INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    startup_script TEXT,
    CONSTRAINT game_templates_pkey PRIMARY KEY (id)
);

CREATE TABLE agent_commands (
    id UUID NOT NULL,
    command_type VARCHAR(255) NOT NULL,
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6),
    payload JSONB,
    status VARCHAR(255) NOT NULL,
    worker_id UUID NOT NULL,
    CONSTRAINT agent_commands_pkey PRIMARY KEY (id),
    CONSTRAINT agent_commands_command_type_check CHECK (command_type IN ('START', 'STOP', 'RUN_TASK', 'BACKUP')),
    CONSTRAINT agent_commands_status_check CHECK (status IN ('QUEUED', 'SENT', 'DONE', 'FAILED')),
    CONSTRAINT fk_agent_commands_worker_id FOREIGN KEY (worker_id) REFERENCES workers(id)
);

CREATE TABLE server_instances (
    id UUID NOT NULL,
    actual_state VARCHAR(255) NOT NULL,
    allocated_ram_mb INTEGER NOT NULL,
    assigned_port INTEGER NOT NULL,
    container_id VARCHAR(255),
    custom_env_vars JSONB,
    desired_state VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    template_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    CONSTRAINT server_instances_pkey PRIMARY KEY (id),
    CONSTRAINT server_instances_actual_state_check CHECK (actual_state IN ('RUNNING', 'STOPPED', 'STARTING', 'FAILED')),
    CONSTRAINT server_instances_desired_state_check CHECK (desired_state IN ('RUNNING', 'STOPPED', 'STARTING', 'FAILED')),
    CONSTRAINT fk_server_instances_template_id FOREIGN KEY (template_id) REFERENCES game_templates(id),
    CONSTRAINT fk_server_instances_worker_id FOREIGN KEY (worker_id) REFERENCES workers(id)
);
