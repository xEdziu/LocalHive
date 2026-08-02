package dev.adrian.goral.localhivebackend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WorkExecutionMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Test
    @DisplayName("V5 adds work execution persistence to the current migration chain")
    void shouldMigrateExistingWorkSchemaToV5() throws SQLException {
        flyway("4").migrate();

        UUID versionId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertApprovedWorkDefinitionVersion(connection, versionId);
            insertWorkInstance(connection, instanceId, versionId);
        }

        Flyway flyway = flyway(null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");

        try (Connection connection = connection()) {
            assertThat(tables(connection))
                    .contains("work_executions")
                    .doesNotContain(
                            "tasks",
                            "worker_tasks",
                            "workloads",
                            "task_assignments",
                            "work_execution_attempts",
                            "resource_reservations"
                    );
            assertThat(columns(connection, "work_executions"))
                    .contains(
                            "definition_version_id",
                            "instance_id",
                            "status",
                            "created_at",
                            "queued_at",
                            "resolved_configuration_snapshot",
                            "resolved_required_ram_mb",
                            "resolved_required_cpu_cores",
                            "resolved_gpu_required",
                            "display_name_snapshot",
                            "failure_code",
                            "failure_message"
                    );
            assertThat(checkConstraints(connection, "work_executions"))
                    .contains(
                            "work_executions_status_check",
                            "work_executions_resolved_configuration_snapshot_check",
                            "work_executions_resolved_required_ram_mb_check",
                            "work_executions_resolved_required_cpu_cores_check",
                            "work_executions_lifecycle_timestamp_check",
                            "work_executions_failure_fields_check",
                            "work_executions_display_name_snapshot_check"
                    );
            assertThat(foreignKeys(connection, "work_executions"))
                    .contains(
                            "fk_work_executions_definition_version_id",
                            "fk_work_executions_instance_id"
                    );

            insertQueuedExecution(connection, UUID.randomUUID(), versionId, null);
            insertQueuedExecution(connection, UUID.randomUUID(), versionId, instanceId);
            insertSucceededExecution(connection, UUID.randomUUID(), versionId);
            insertFailedExecution(connection, UUID.randomUUID(), versionId);

            assertThatThrownBy(() -> insertExecutionWithStatus(connection, versionId, "CREATED"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExecutionWithSnapshot(connection, versionId, "[]"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExecutionWithRam(connection, versionId, -1))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExecutionWithCpu(connection, versionId, -1))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertQueuedExecution(connection, UUID.randomUUID(), UUID.randomUUID(), null))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertQueuedExecution(connection, UUID.randomUUID(), versionId, UUID.randomUUID()))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertFailedExecutionWithoutFailureCode(connection, UUID.randomUUID(), versionId))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertRunningExecutionWithoutStartedAt(connection, UUID.randomUUID(), versionId))
                    .isInstanceOf(SQLException.class);
            insertCancelledExecutionWithCompletedAt(connection, UUID.randomUUID(), versionId);
            insertCancelledExecutionWithFailureFields(connection, UUID.randomUUID(), versionId);
            assertThatThrownBy(() -> insertCancelledExecutionWithBlankFailureCode(connection, UUID.randomUUID(), versionId))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static Flyway flyway(String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");

        if (targetVersion != null) {
            configuration.target(targetVersion);
        }

        return configuration.load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }

    private static void insertApprovedWorkDefinitionVersion(Connection connection, UUID versionId) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        try (var statement = connection.prepareStatement("""
                insert into users (
                    id,
                    username,
                    password_hash,
                    created_at
                ) values (?, ?, ?, current_timestamp)
                """)) {
            statement.setObject(1, userId);
            statement.setString(2, "execution-migration-user-" + UUID.randomUUID());
            statement.setString(3, "hashed-password");
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement("""
                insert into work_definitions (
                    id,
                    logical_identifier,
                    work_type,
                    source_type,
                    created_at
                ) values (?, ?, ?, ?, current_timestamp)
                """)) {
            statement.setObject(1, definitionId);
            statement.setString(2, "localhive.migrated-execution-" + UUID.randomUUID());
            statement.setString(3, "TASK");
            statement.setString(4, "LOCAL");
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement("""
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
                """)) {
            statement.setObject(1, versionId);
            statement.setObject(2, definitionId);
            statement.setInt(3, 1);
            statement.setString(4, "Migrated Execution Definition");
            statement.setString(5, "localhive.migrated-execution-executor");
            statement.setInt(6, 1);
            statement.setInt(7, 128);
            statement.setInt(8, 1);
            statement.setBoolean(9, false);
            statement.setString(10, "0".repeat(64));
            statement.setString(11, "APPROVED");
            statement.setObject(12, userId);
            statement.setObject(13, userId);
            statement.executeUpdate();
        }
    }

    private static void insertWorkInstance(Connection connection, UUID instanceId, UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_instances (
                    id,
                    definition_version_id,
                    display_name,
                    enabled,
                    configuration_overrides,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, '{}'::jsonb, current_timestamp, current_timestamp)
                """)) {
            statement.setObject(1, instanceId);
            statement.setObject(2, versionId);
            statement.setString(3, "Migrated Execution Instance");
            statement.setBoolean(4, true);
            statement.executeUpdate();
        }
    }

    private static void insertQueuedExecution(Connection connection,
                                              UUID executionId,
                                              UUID versionId,
                                              UUID instanceId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    instance_id,
                    status,
                    created_at,
                    queued_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, ?, current_timestamp, current_timestamp, '{"mode":"test"}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            if (instanceId == null) {
                statement.setNull(3, Types.OTHER);
            } else {
                statement.setObject(3, instanceId);
            }
            statement.setString(4, "QUEUED");
            statement.setInt(5, 128);
            statement.setInt(6, 1);
            statement.setBoolean(7, false);
            statement.executeUpdate();
        }
    }

    private static void insertSucceededExecution(Connection connection,
                                                 UUID executionId,
                                                 UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    assigned_at,
                    claimed_at,
                    started_at,
                    completed_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "SUCCEEDED");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertFailedExecution(Connection connection,
                                              UUID executionId,
                                              UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    assigned_at,
                    claimed_at,
                    started_at,
                    completed_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required,
                    failure_code,
                    failure_message
                ) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "FAILED");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.setString(7, "EXECUTOR_ERROR");
            statement.setString(8, "Process exited with status 1");
            statement.executeUpdate();
        }
    }

    private static void insertExecutionWithStatus(Connection connection,
                                                  UUID versionId,
                                                  String status) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, versionId);
            statement.setString(3, status);
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertExecutionWithSnapshot(Connection connection,
                                                    UUID versionId,
                                                    String snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
                ) values (?, ?, ?, current_timestamp, current_timestamp, cast(? as jsonb), ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, versionId);
            statement.setString(3, "QUEUED");
            statement.setString(4, snapshot);
            statement.setInt(5, 0);
            statement.setInt(6, 0);
            statement.setBoolean(7, false);
            statement.executeUpdate();
        }
    }

    private static void insertExecutionWithRam(Connection connection, UUID versionId, int ramMb) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, versionId);
            statement.setString(3, "QUEUED");
            statement.setInt(4, ramMb);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertExecutionWithCpu(Connection connection, UUID versionId, int cpuCores) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, versionId);
            statement.setString(3, "QUEUED");
            statement.setInt(4, 0);
            statement.setInt(5, cpuCores);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertFailedExecutionWithoutFailureCode(Connection connection,
                                                                UUID executionId,
                                                                UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    started_at,
                    completed_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "FAILED");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertRunningExecutionWithoutStartedAt(Connection connection,
                                                               UUID executionId,
                                                               UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "RUNNING");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertCancelledExecutionWithCompletedAt(Connection connection,
                                                                UUID executionId,
                                                                UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    completed_at,
                    cancelled_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "CANCELLED");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.executeUpdate();
        }
    }

    private static void insertCancelledExecutionWithFailureFields(Connection connection,
                                                                  UUID executionId,
                                                                  UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    completed_at,
                    cancelled_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required,
                    failure_code,
                    failure_message
                ) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "CANCELLED");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.setString(7, "ADMIN_CANCELLED");
            statement.setString(8, "Execution cancelled by admin.");
            statement.executeUpdate();
        }
    }

    private static void insertCancelledExecutionWithBlankFailureCode(Connection connection,
                                                                     UUID executionId,
                                                                     UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    status,
                    created_at,
                    queued_at,
                    completed_at,
                    cancelled_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required,
                    failure_code,
                    failure_message
                ) values (?, ?, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "CANCELLED");
            statement.setInt(4, 0);
            statement.setInt(5, 0);
            statement.setBoolean(6, false);
            statement.setString(7, " ");
            statement.setString(8, "Execution cancelled by admin.");
            statement.executeUpdate();
        }
    }

    private static List<String> tables(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                """)) {
            try (var resultSet = statement.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (resultSet.next()) {
                    tables.add(resultSet.getString("table_name"));
                }
                return tables;
            }
        }
    }

    private static List<String> columns(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("column_name"));
                }
                return columns;
            }
        }
    }

    private static List<String> checkConstraints(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = ?
                  and constraint_type = 'CHECK'
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                List<String> constraints = new ArrayList<>();
                while (resultSet.next()) {
                    constraints.add(resultSet.getString("constraint_name"));
                }
                return constraints;
            }
        }
    }

    private static List<String> foreignKeys(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = ?
                  and constraint_type = 'FOREIGN KEY'
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                List<String> constraints = new ArrayList<>();
                while (resultSet.next()) {
                    constraints.add(resultSet.getString("constraint_name"));
                }
                return constraints;
            }
        }
    }
}
