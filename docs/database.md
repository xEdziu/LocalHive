# Database Schema

LocalHive Master uses PostgreSQL as the application database. Flyway owns schema creation and migrations; Hibernate is configured with `ddl-auto=validate` and must not mutate the schema at runtime.

The current baseline migration is:

```text
localhive-backend/src/main/resources/db/migration/V1__baseline.sql
```

This baseline contains only the schema used by the current implementation. Task, Workload, metrics, and compute-grid tables are intentionally excluded until their domains are designed and implemented.

## Integration Tests

Database integration tests use Testcontainers PostgreSQL with the explicit `postgres:16.2-alpine` image. Docker Engine must be available when running the full Maven test suite.

No manually running LocalHive PostgreSQL instance is required for tests. Testcontainers provides a fresh PostgreSQL instance with a dynamic JDBC URL, Flyway initializes it from the migration set, and Hibernate validates the migrated schema with `ddl-auto=validate`.

## Current Tables

| Table | Purpose |
| --- | --- |
| `users` | First-time setup and admin authentication users. |
| `system_settings` | Key-value system configuration persisted by setup and application services. |
| `workers` | Registered LocalHive Agent workers and their current lifecycle state. |
| `agent_commands` | Commands queued for workers. |
| `game_templates` | Game server template definitions. |
| `server_instances` | Game server instances assigned to workers and templates. |

## Entity Relationship Diagram

```mermaid
erDiagram
    USERS {
        UUID id PK
        timestamp created_at
        string password_hash
        string username UK
    }

    SYSTEM_SETTINGS {
        string config_key PK
        string config_value
        timestamp updated_at
    }

    WORKERS {
        UUID id PK
        string api_key_hash
        int cpu_cores
        string gpu_name
        string hostname UK
        string ip_address
        timestamp last_heartbeat_at
        string os_type
        int shared_ram_mb
        string status
        int total_ram_mb
    }

    GAME_TEMPLATES {
        UUID id PK
        int default_port
        string docker_image
        int min_ram_mb
        string name
        string startup_script
    }

    AGENT_COMMANDS {
        UUID id PK
        string command_type
        timestamp completed_at
        timestamp created_at
        jsonb payload
        string status
        UUID worker_id FK
    }

    SERVER_INSTANCES {
        UUID id PK
        string actual_state
        int allocated_ram_mb
        int assigned_port
        string container_id
        jsonb custom_env_vars
        string desired_state
        string display_name
        UUID template_id FK
        UUID worker_id FK
    }

    WORKERS ||--o{ AGENT_COMMANDS : receives
    WORKERS ||--o{ SERVER_INSTANCES : hosts
    GAME_TEMPLATES ||--o{ SERVER_INSTANCES : templates
```

## Constraints

| Table | Constraint |
| --- | --- |
| `users` | Primary key on `id`; unique `username`. |
| `system_settings` | Primary key on `config_key`. |
| `workers` | Primary key on `id`; unique `hostname`; `status` check for `PENDING`, `ACTIVE`, `PAUSED`, `OFFLINE`. |
| `agent_commands` | Primary key on `id`; foreign key to `workers(id)`; enum checks for `command_type` and `status`. |
| `game_templates` | Primary key on `id`. |
| `server_instances` | Primary key on `id`; foreign keys to `workers(id)` and `game_templates(id)`; enum checks for `desired_state` and `actual_state`. |
