```mermaid
erDiagram
    SYSTEM_SETTINGS {
        string config_key PK
        string config_value
        timestamp updated_at
    }

    USERS {
        UUID id PK
        string username
        string password_hash
        timestamp created_at
    }

    %% --- CORE ---
    WORKERS {
        UUID id PK
        string hostname
        string ip_address
        string os_type
        int total_ram_mb
        int shared_ram_mb
        int cpu_cores
        string gpu_name
        string status "CHECK: PENDING, ACTIVE, OFFLINE"
        string api_key_hash
        timestamp last_heartbeat_at
    }

    AGENT_COMMANDS {
        UUID id PK
        UUID worker_id FK
        string command_type "START, STOP, RUN_TASK, BACKUP"
        jsonb payload
        string status "CHECK: QUEUED, SENT, DONE, FAILED"
        timestamp created_at
        timestamp completed_at
    }

    GAME_TEMPLATES {
        UUID id PK
        string name
        string docker_image
        int default_port
        int min_ram_mb
        string startup_script
    }

    SERVER_INSTANCES {
        UUID id PK
        UUID worker_id FK
        UUID template_id FK
        string display_name
        int allocated_ram_mb
        int assigned_port
        string container_id
        string desired_state "CHECK: RUNNING, STOPPED"
        string actual_state "CHECK: RUNNING, STOPPED, STARTING, FAILED"
        jsonb custom_env_vars
    }

    %% --- RESEARCH METRICS ---
    WORKER_METRICS {
        UUID id PK
        UUID worker_id FK
        float cpu_usage_percent
        int ram_used_mb
        float gpu_usage_percent
        timestamp recorded_at
    }

    PROTOCOL_MEASUREMENTS {
        UUID id PK
        string protocol "REST, WEBSOCKET, SOAP"
        string payload_format "JSON, BINARY"
        string operation_type
        int latency_ms
        int payload_size_bytes
        timestamp created_at
    }

    %% --- COMPUTE GRID ---
    COMPUTE_JOBS {
        UUID id PK
        string name
        string docker_image
        string payload_path
        string status "CHECK: QUEUED, IN_PROGRESS, COMPLETED"
        timestamp created_at
    }

    COMPUTE_TASKS {
        UUID id PK
        UUID job_id FK
        UUID worker_id FK "Might be null if not yet assigned"
        jsonb parameters
        string status "CHECK: PENDING, RUNNING, DONE, FAILED"
        int attempt_count
        timestamp lease_until
        string result_path
        timestamp assigned_at
    }

    %% Relacje - Rdzeń (Core)
    WORKERS ||--o{ SERVER_INSTANCES : "hosts" }
    GAME_TEMPLATES ||--o{ SERVER_INSTANCES : "template for" }
    WORKERS ||--o{ AGENT_COMMANDS : "recieves" }

    %% Relacje - Badania i Obliczenia
    WORKERS ||--o{ WORKER_METRICS : "reports" }
    WORKERS ||--o{ COMPUTE_TASKS : "executes" }
    COMPUTE_JOBS ||--|{ COMPUTE_TASKS : "contains" }
```
