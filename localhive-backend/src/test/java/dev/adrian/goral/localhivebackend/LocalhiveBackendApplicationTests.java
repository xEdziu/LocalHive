package dev.adrian.goral.localhivebackend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class LocalhiveBackendApplicationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    void flywayMigratesFreshPostgresAndHibernateValidatesSchema() throws SQLException {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");

        try (var connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();
            assertThat(jdbcUrl)
                    .startsWith("jdbc:postgresql://")
                    .doesNotContain("localhost:5432");
        }

        Integer appliedMigrationCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version in ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14') and success = true",
                Integer.class
        );
        assertThat(appliedMigrationCount).isEqualTo(14);

        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class
        );
        assertThat(tables).contains(
                "flyway_schema_history",
                "users",
                "system_settings",
                "workers",
                "agent_commands",
                "game_templates",
                "server_instances",
                "work_definitions",
                "work_definition_versions",
                "work_instances",
                "execution_groups",
                "execution_group_merge_plans",
                "work_executions",
                "execution_assignments",
                "execution_attempts",
                "artifacts",
                "execution_artifacts",
                "worker_capabilities"
        );

        List<String> workerColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'workers'",
                String.class
        );
        assertThat(workerColumns)
                .contains("approval_status", "connection_status", "availability_status")
                .doesNotContain("status");

        List<String> workerCheckConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'workers'
                  and constraint_type = 'CHECK'
                """, String.class);
        assertThat(workerCheckConstraints)
                .contains(
                        "workers_approval_status_check",
                        "workers_connection_status_check",
                        "workers_availability_status_check"
                )
                .doesNotContain("workers_status_check");

        List<String> workDefinitionVersionColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'work_definition_versions'",
                String.class
        );
        assertThat(workDefinitionVersionColumns)
                .contains("default_required_ram_mb", "default_required_cpu_cores", "default_gpu_required");

        List<String> workInstanceColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'work_instances'",
                String.class
        );
        assertThat(workInstanceColumns)
                .contains(
                        "id",
                        "definition_version_id",
                        "display_name",
                        "enabled",
                        "configuration_overrides",
                        "override_required_ram_mb",
                        "override_required_cpu_cores",
                        "override_gpu_required",
                        "created_at",
                        "updated_at"
                );

        List<String> workExecutionColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'work_executions'",
                String.class
        );
        assertThat(workExecutionColumns)
                .contains(
                        "id",
                        "definition_version_id",
                        "instance_id",
                        "status",
                        "created_at",
                        "queued_at",
                        "assigned_at",
                        "claimed_at",
                        "started_at",
                        "completed_at",
                        "cancelled_at",
                        "expired_at",
                        "resolved_configuration_snapshot",
                        "resolved_required_ram_mb",
                        "resolved_required_cpu_cores",
                        "resolved_gpu_required",
                        "display_name_snapshot",
                        "execution_group_id",
                        "group_role",
                        "shard_index",
                        "shard_count",
                        "failure_code",
                        "failure_message"
                );

        List<String> workExecutionCheckConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'work_executions'
                  and constraint_type = 'CHECK'
                """, String.class);
        assertThat(workExecutionCheckConstraints)
                .contains(
                        "work_executions_status_check",
                        "work_executions_resolved_configuration_snapshot_check",
                        "work_executions_resolved_required_ram_mb_check",
                        "work_executions_resolved_required_cpu_cores_check",
                        "work_executions_lifecycle_timestamp_check",
                        "work_executions_failure_fields_check",
                        "work_executions_display_name_snapshot_check",
                        "work_executions_group_role_check",
                        "work_executions_group_metadata_check"
                );

        List<String> executionGroupColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'execution_groups'",
                String.class
        );
        assertThat(executionGroupColumns)
                .contains(
                        "id",
                        "display_name",
                        "status",
                        "merge_mode",
                        "failure_policy",
                        "shard_count",
                        "created_at",
                        "updated_at",
                        "completed_at",
                        "cancelled_at",
                        "failure_code",
                        "failure_message"
                );

        List<String> executionGroupCheckConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'execution_groups'
                  and constraint_type = 'CHECK'
                """, String.class);
        assertThat(executionGroupCheckConstraints)
                .contains(
                        "execution_groups_display_name_check",
                        "execution_groups_status_check",
                        "execution_groups_merge_mode_check",
                        "execution_groups_failure_policy_check",
                        "execution_groups_shard_count_check",
                        "execution_groups_failure_fields_check"
                );

        List<String> executionGroupMergePlanColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'execution_group_merge_plans'",
                String.class
        );
        assertThat(executionGroupMergePlanColumns)
                .contains(
                        "execution_group_id",
                        "definition_version_id",
                        "configuration_template",
                        "created_at"
                );

        List<String> executionGroupMergePlanConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'execution_group_merge_plans'
                """, String.class);
        assertThat(executionGroupMergePlanConstraints)
                .contains(
                        "execution_group_merge_plans_pkey",
                        "fk_execution_group_merge_plans_execution_group_id",
                        "fk_execution_group_merge_plans_definition_version_id",
                        "execution_group_merge_plans_configuration_template_object_check"
                );

        List<String> executionAssignmentColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'execution_assignments'",
                String.class
        );
        assertThat(executionAssignmentColumns)
                .contains(
                        "id",
                        "execution_id",
                        "worker_id",
                        "assignment_mode",
                        "assigned_at",
                        "claimed_at",
                        "lease_expires_at",
                        "lease_token_hash"
                );

        List<String> executionAttemptColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'execution_attempts'",
                String.class
        );
        assertThat(executionAttemptColumns)
                .contains(
                        "id",
                        "execution_id",
                        "assignment_id",
                        "attempt_number",
                        "status",
                        "started_at",
                        "completed_at",
                        "failure_code",
                        "failure_message"
                );

        List<String> executionAssignmentCheckConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'execution_assignments'
                  and constraint_type = 'CHECK'
                """, String.class);
        assertThat(executionAssignmentCheckConstraints)
                .contains(
                        "execution_assignments_assignment_mode_check",
                        "execution_assignments_lease_fields_check"
                );

        List<String> executionAttemptCheckConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'execution_attempts'
                  and constraint_type = 'CHECK'
                """, String.class);
        assertThat(executionAttemptCheckConstraints)
                .contains(
                        "execution_attempts_attempt_number_check",
                        "execution_attempts_status_check",
                        "execution_attempts_lifecycle_check"
                );

        List<String> artifactColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'artifacts'",
                String.class
        );
        assertThat(artifactColumns)
                .contains(
                        "id",
                        "kind",
                        "original_filename",
                        "content_type",
                        "size_bytes",
                        "sha256",
                        "storage_path",
                        "created_at",
                        "created_by"
                );

        List<String> artifactCheckConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'artifacts'
                  and constraint_type = 'CHECK'
                """, String.class);
        assertThat(artifactCheckConstraints)
                .contains(
                        "artifacts_kind_check",
                        "artifacts_kind_not_blank_check",
                        "artifacts_original_filename_not_blank_check",
                        "artifacts_content_type_not_blank_check",
                        "artifacts_size_bytes_check",
                        "artifacts_sha256_length_check",
                        "artifacts_storage_path_not_blank_check"
                );

        List<String> artifactIndexes = jdbcTemplate.queryForList("""
                select indexname
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'artifacts'
                """, String.class);
        assertThat(artifactIndexes)
                .contains(
                        "idx_artifacts_kind",
                        "idx_artifacts_created_at",
                        "idx_artifacts_sha256"
                );

        List<String> executionArtifactColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'execution_artifacts'",
                String.class
        );
        assertThat(executionArtifactColumns)
                .contains(
                        "id",
                        "execution_id",
                        "artifact_id",
                        "uploaded_by_worker_id",
                        "relative_path",
                        "created_at"
                );

        List<String> executionArtifactConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'execution_artifacts'
                """, String.class);
        assertThat(executionArtifactConstraints)
                .contains(
                        "execution_artifacts_pkey",
                        "uk_execution_artifacts_artifact_id",
                        "fk_execution_artifacts_execution_id",
                        "fk_execution_artifacts_artifact_id",
                        "fk_execution_artifacts_uploaded_by_worker_id",
                        "execution_artifacts_relative_path_not_blank_check"
                );

        List<String> workerCapabilitiesColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'worker_capabilities'",
                String.class
        );
        assertThat(workerCapabilitiesColumns)
                .contains(
                        "worker_id",
                        "reported_at",
                        "executors",
                        "docker_enabled",
                        "docker_allowed_images",
                        "docker_max_memory_mb",
                        "docker_max_cpu_cores",
                        "docker_gpu_allowed"
                );

        List<String> workerCapabilitiesConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = 'worker_capabilities'
                """, String.class);
        assertThat(workerCapabilitiesConstraints)
                .contains(
                        "worker_capabilities_pkey",
                        "fk_worker_capabilities_worker",
                        "chk_worker_capabilities_executors_array_size",
                        "chk_worker_capabilities_docker_allowed_images_array_size",
                        "chk_worker_capabilities_docker_max_memory_non_negative",
                        "chk_worker_capabilities_docker_max_cpu_non_negative"
                );
    }

    @Test
    void workDefinitionLogicalIdentifierConstraintRequiresNamespacedIdentifier() {
        jdbcTemplate.update("""
                insert into work_definitions (
                    id,
                    logical_identifier,
                    work_type,
                    source_type,
                    created_at
                ) values (?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                "localhive.constraint-valid",
                "TASK",
                "LOCAL"
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into work_definitions (
                    id,
                    logical_identifier,
                    work_type,
                    source_type,
                    created_at
                ) values (?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                "minecraft",
                "TASK",
                "LOCAL"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void workerCapabilityConstraintsRequireArrayPayloadsAndNonNegativeDockerLimits() {
        UUID validWorkerId = UUID.randomUUID();
        insertSchemaWorker(validWorkerId, "capabilities-valid");

        jdbcTemplate.update("""
                insert into worker_capabilities (
                    worker_id,
                    reported_at,
                    executors,
                    docker_enabled,
                    docker_allowed_images,
                    docker_max_memory_mb,
                    docker_max_cpu_cores,
                    docker_gpu_allowed
                ) values (?, current_timestamp, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                """,
                validWorkerId,
                "[{\"executorId\":\"localhive.no-op\",\"executorContractVersion\":1,\"enabled\":true}]",
                true,
                "[\"alpine:3.20\"]",
                4096,
                8,
                false
        );

        UUID invalidExecutorsWorkerId = UUID.randomUUID();
        insertSchemaWorker(invalidExecutorsWorkerId, "capabilities-invalid-executors");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into worker_capabilities (
                    worker_id,
                    reported_at,
                    executors
                ) values (?, current_timestamp, ?::jsonb)
                """,
                invalidExecutorsWorkerId,
                "{}"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID invalidImagesWorkerId = UUID.randomUUID();
        insertSchemaWorker(invalidImagesWorkerId, "capabilities-invalid-images");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into worker_capabilities (
                    worker_id,
                    reported_at,
                    executors,
                    docker_allowed_images
                ) values (?, current_timestamp, ?::jsonb, ?::jsonb)
                """,
                invalidImagesWorkerId,
                "[]",
                "{}"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID negativeMemoryWorkerId = UUID.randomUUID();
        insertSchemaWorker(negativeMemoryWorkerId, "capabilities-negative-memory");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into worker_capabilities (
                    worker_id,
                    reported_at,
                    executors,
                    docker_max_memory_mb
                ) values (?, current_timestamp, ?::jsonb, ?)
                """,
                negativeMemoryWorkerId,
                "[]",
                -1
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void artifactConstraintsRequireValidMetadata() {
        jdbcTemplate.update("""
                insert into artifacts (
                    id,
                    kind,
                    original_filename,
                    content_type,
                    size_bytes,
                    sha256,
                    storage_path,
                    created_at,
                    created_by
                ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)
                """,
                UUID.randomUUID(),
                "WORKSPACE_PACKAGE",
                "workspace.zip",
                "application/zip",
                12L,
                "0".repeat(64),
                UUID.randomUUID() + "/package.zip",
                "schema-test"
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into artifacts (
                    id,
                    kind,
                    original_filename,
                    size_bytes,
                    sha256,
                    storage_path,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                "INVALID",
                "workspace.zip",
                12L,
                "0".repeat(64),
                UUID.randomUUID() + "/package.zip"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbcTemplate.update("""
                insert into artifacts (
                    id,
                    kind,
                    original_filename,
                    content_type,
                    size_bytes,
                    sha256,
                    storage_path,
                    created_at,
                    created_by
                ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)
                """,
                UUID.randomUUID(),
                "EXECUTION_OUTPUT",
                "output.txt",
                "text/plain",
                12L,
                "1".repeat(64),
                UUID.randomUUID() + "/artifact",
                "schema-test"
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into artifacts (
                    id,
                    kind,
                    original_filename,
                    size_bytes,
                    sha256,
                    storage_path,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                "WORKSPACE_PACKAGE",
                "workspace.zip",
                -1L,
                "0".repeat(64),
                UUID.randomUUID() + "/package.zip"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into artifacts (
                    id,
                    kind,
                    original_filename,
                    size_bytes,
                    sha256,
                    storage_path,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                "WORKSPACE_PACKAGE",
                "workspace.zip",
                12L,
                "0".repeat(63),
                UUID.randomUUID() + "/package.zip"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void executionArtifactConstraintsLinkExecutionArtifactAndWorker() {
        UUID userId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into users (
                    id,
                    username,
                    password_hash,
                    created_at
                ) values (?, ?, ?, current_timestamp)
                """,
                userId,
                "execution-artifact-user-" + UUID.randomUUID(),
                "hashed-password"
        );
        jdbcTemplate.update("""
                insert into workers (
                    id,
                    api_key_hash,
                    cpu_cores,
                    hostname,
                    ip_address,
                    os_type,
                    shared_ram_mb,
                    approval_status,
                    connection_status,
                    availability_status,
                    total_ram_mb
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                workerId,
                "hashed-api-key",
                16,
                "execution-artifact-worker-" + UUID.randomUUID(),
                "192.168.1.10",
                "Linux",
                8192,
                "APPROVED",
                "ONLINE",
                "AVAILABLE",
                32768
        );
        jdbcTemplate.update("""
                insert into work_definitions (
                    id,
                    logical_identifier,
                    work_type,
                    source_type,
                    created_at
                ) values (?, ?, ?, ?, current_timestamp)
                """,
                definitionId,
                "localhive.execution-artifact-" + UUID.randomUUID(),
                "TASK",
                "LOCAL"
        );
        jdbcTemplate.update("""
                insert into work_definition_versions (
                    id,
                    definition_id,
                    version_number,
                    name,
                    executor_id,
                    executor_contract_version,
                    executor_configuration,
                    default_required_ram_mb,
                    default_required_cpu_cores,
                    default_gpu_required,
                    content_checksum,
                    approval_status,
                    created_at,
                    created_by_user_id,
                    reviewed_at,
                    reviewed_by_user_id
                ) values (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?, ?, current_timestamp, ?, current_timestamp, ?)
                """,
                versionId,
                definitionId,
                1,
                "Execution Artifact Definition",
                "localhive.execution-artifact-executor",
                1,
                0,
                0,
                false,
                "0".repeat(64),
                "APPROVED",
                userId,
                userId
        );
        jdbcTemplate.update("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """,
                executionId,
                versionId,
                "QUEUED",
                0,
                0,
                false
        );
        insertOutputArtifact(artifactId, "execution-artifact");

        jdbcTemplate.update("""
                insert into execution_artifacts (
                    id,
                    execution_id,
                    artifact_id,
                    uploaded_by_worker_id,
                    relative_path,
                    created_at
                ) values (?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                executionId,
                artifactId,
                workerId,
                "results/output.txt"
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into execution_artifacts (
                    id,
                    execution_id,
                    artifact_id,
                    uploaded_by_worker_id,
                    relative_path,
                    created_at
                ) values (?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                executionId,
                artifactId,
                workerId,
                "results/duplicate.txt"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID blankPathArtifactId = UUID.randomUUID();
        insertOutputArtifact(blankPathArtifactId, "execution-artifact-blank-path");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into execution_artifacts (
                    id,
                    execution_id,
                    artifact_id,
                    uploaded_by_worker_id,
                    relative_path,
                    created_at
                ) values (?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                executionId,
                blankPathArtifactId,
                workerId,
                "   "
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID missingExecutionArtifactId = UUID.randomUUID();
        insertOutputArtifact(missingExecutionArtifactId, "execution-artifact-missing-execution");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into execution_artifacts (
                    id,
                    execution_id,
                    artifact_id,
                    uploaded_by_worker_id,
                    relative_path,
                    created_at
                ) values (?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                missingExecutionArtifactId,
                workerId,
                "results/missing-execution.txt"
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void workInstanceConstraintsRequireObjectOverridesAndNonNegativeResourceOverrides() {
        UUID userId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into users (
                    id,
                    username,
                    password_hash,
                    created_at
                ) values (?, ?, ?, current_timestamp)
                """,
                userId,
                "schema-user-" + UUID.randomUUID(),
                "hashed-password"
        );
        jdbcTemplate.update("""
                insert into work_definitions (
                    id,
                    logical_identifier,
                    work_type,
                    source_type,
                    created_at
                ) values (?, ?, ?, ?, current_timestamp)
                """,
                definitionId,
                "localhive.constraint-instance",
                "TASK",
                "LOCAL"
        );
        jdbcTemplate.update("""
                insert into work_definition_versions (
                    id,
                    definition_id,
                    version_number,
                    name,
                    executor_id,
                    executor_contract_version,
                    executor_configuration,
                    content_checksum,
                    approval_status,
                    created_at,
                    created_by_user_id,
                    reviewed_at,
                    reviewed_by_user_id
                ) values (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, current_timestamp, ?, current_timestamp, ?)
                """,
                versionId,
                definitionId,
                1,
                "Constraint Version",
                "localhive.constraint-executor",
                1,
                "0".repeat(64),
                "APPROVED",
                userId,
                userId
        );

        jdbcTemplate.update("""
                insert into work_instances (
                    id,
                    definition_version_id,
                    display_name,
                    enabled,
                    configuration_overrides,
                    override_required_ram_mb,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, '{}'::jsonb, ?, current_timestamp, current_timestamp)
                """,
                UUID.randomUUID(),
                versionId,
                "Constraint Instance",
                true,
                0
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into work_instances (
                    id,
                    definition_version_id,
                    display_name,
                    enabled,
                    configuration_overrides,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, '[]'::jsonb, current_timestamp, current_timestamp)
                """,
                UUID.randomUUID(),
                versionId,
                "Invalid JSON",
                true
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into work_instances (
                    id,
                    definition_version_id,
                    display_name,
                    enabled,
                    configuration_overrides,
                    override_required_ram_mb,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, '{}'::jsonb, ?, current_timestamp, current_timestamp)
                """,
                UUID.randomUUID(),
                versionId,
                "Invalid Resources",
                true,
                -1
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertOutputArtifact(UUID artifactId, String filenamePrefix) {
        jdbcTemplate.update("""
                insert into artifacts (
                    id,
                    kind,
                    original_filename,
                    content_type,
                    size_bytes,
                    sha256,
                    storage_path,
                    created_at,
                    created_by
                ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)
                """,
                artifactId,
                "EXECUTION_OUTPUT",
                filenamePrefix + ".txt",
                "text/plain",
                12L,
                "2".repeat(64),
                artifactId + "/artifact",
                "schema-test"
        );
    }

    private void insertSchemaWorker(UUID workerId, String suffix) {
        jdbcTemplate.update("""
                insert into workers (
                    id,
                    api_key_hash,
                    cpu_cores,
                    hostname,
                    ip_address,
                    os_type,
                    shared_ram_mb,
                    approval_status,
                    connection_status,
                    availability_status,
                    total_ram_mb
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                workerId,
                "hashed-api-key",
                16,
                "schema-worker-" + suffix + "-" + UUID.randomUUID(),
                "192.168.1.10",
                "Linux",
                8192,
                "APPROVED",
                "ONLINE",
                "AVAILABLE",
                32768
        );
    }
}
