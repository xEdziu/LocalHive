CREATE TABLE worker_capabilities (
    worker_id UUID PRIMARY KEY,
    reported_at TIMESTAMP NOT NULL,
    executors JSONB NOT NULL,
    docker_enabled BOOLEAN,
    docker_allowed_images JSONB,
    docker_max_memory_mb INTEGER,
    docker_max_cpu_cores INTEGER,
    docker_gpu_allowed BOOLEAN,

    CONSTRAINT fk_worker_capabilities_worker
        FOREIGN KEY (worker_id)
        REFERENCES workers(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_worker_capabilities_executors_array_size
        CHECK (
            CASE
                WHEN jsonb_typeof(executors) = 'array'
                    THEN jsonb_array_length(executors) <= 50
                ELSE false
            END
        ),

    CONSTRAINT chk_worker_capabilities_docker_allowed_images_array_size
        CHECK (
            CASE
                WHEN docker_allowed_images IS NULL
                    THEN true
                WHEN jsonb_typeof(docker_allowed_images) = 'array'
                    THEN jsonb_array_length(docker_allowed_images) <= 100
                ELSE false
            END
        ),

    CONSTRAINT chk_worker_capabilities_docker_max_memory_non_negative
        CHECK (docker_max_memory_mb IS NULL OR docker_max_memory_mb >= 0),

    CONSTRAINT chk_worker_capabilities_docker_max_cpu_non_negative
        CHECK (docker_max_cpu_cores IS NULL OR docker_max_cpu_cores >= 0)
);
