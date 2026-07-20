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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ExecutionAssignmentAttemptMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Test
    @DisplayName("V6 adds execution assignments and attempts to the current migration chain")
    void shouldMigrateWorkExecutionsToV6WithAssignmentAndAttemptConstraints() throws SQLException {
        flyway("5").migrate();

        UUID workerId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertWorker(connection, workerId, "v6-worker");
            insertApprovedWorkDefinitionVersion(connection, versionId);
            insertAssignedExecution(connection, executionId, versionId);
        }

        Flyway flyway = flyway(null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");

        try (Connection connection = connection()) {
            assertThat(tables(connection))
                    .contains("execution_assignments", "execution_attempts")
                    .doesNotContain(
                            "tasks",
                            "worker_tasks",
                            "workloads",
                            "task_assignments",
                            "leases",
                            "resource_reservations"
                    );
            assertThat(columns(connection, "execution_assignments"))
                    .contains(
                            "id",
                            "execution_id",
                            "worker_id",
                            "assignment_mode",
                            "assigned_at"
                    );
            assertThat(columns(connection, "execution_attempts"))
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
            assertThat(checkConstraints(connection, "execution_assignments"))
                    .contains("execution_assignments_assignment_mode_check");
            assertThat(checkConstraints(connection, "execution_attempts"))
                    .contains(
                            "execution_attempts_attempt_number_check",
                            "execution_attempts_status_check",
                            "execution_attempts_lifecycle_check"
                    );
            assertThat(uniqueConstraints(connection, "execution_assignments"))
                    .contains("uk_execution_assignments_execution_id");
            assertThat(uniqueConstraints(connection, "execution_attempts"))
                    .contains(
                            "uk_execution_attempts_execution_id",
                            "uk_execution_attempts_execution_attempt_number"
                    );
            assertThat(foreignKeys(connection, "execution_assignments"))
                    .contains(
                            "fk_execution_assignments_execution_id",
                            "fk_execution_assignments_worker_id"
                    );
            assertThat(foreignKeys(connection, "execution_attempts"))
                    .contains(
                            "fk_execution_attempts_execution_id",
                            "fk_execution_attempts_assignment_id"
                    );

            UUID assignmentId = UUID.randomUUID();
            insertAssignment(connection, assignmentId, executionId, workerId, "AUTO");
            insertRunningAttempt(connection, UUID.randomUUID(), executionId, assignmentId, 1);

            assertThatThrownBy(() -> insertAssignment(
                    connection,
                    UUID.randomUUID(),
                    executionId,
                    workerId,
                    "AUTO"
            )).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertAssignment(
                    connection,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    workerId,
                    "AUTO"
            )).isInstanceOf(SQLException.class);
            UUID invalidModeExecutionId = UUID.randomUUID();
            insertAssignedExecution(connection, invalidModeExecutionId, versionId);
            assertThatThrownBy(() -> insertAssignment(
                    connection,
                    UUID.randomUUID(),
                    invalidModeExecutionId,
                    workerId,
                    "DIRECT"
            )).isInstanceOf(SQLException.class);

            UUID secondExecutionId = UUID.randomUUID();
            insertAssignedExecution(connection, secondExecutionId, versionId);
            UUID secondAssignmentId = UUID.randomUUID();
            insertAssignment(connection, secondAssignmentId, secondExecutionId, workerId, "REQUIRE");

            assertThatThrownBy(() -> insertRunningAttempt(
                    connection,
                    UUID.randomUUID(),
                    secondExecutionId,
                    secondAssignmentId,
                    2
            )).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertAttemptWithStatus(
                    connection,
                    UUID.randomUUID(),
                    secondExecutionId,
                    secondAssignmentId,
                    "STARTED",
                    null,
                    null,
                    null
            )).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertAttemptWithStatus(
                    connection,
                    UUID.randomUUID(),
                    secondExecutionId,
                    secondAssignmentId,
                    "FAILED",
                    LocalDateTime.parse("2026-07-17T10:04:00"),
                    "EXECUTOR_ERROR",
                    " "
            )).isInstanceOf(SQLException.class);
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

    private static void insertWorker(Connection connection, UUID workerId, String hostname) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
                """)) {
            statement.setObject(1, workerId);
            statement.setString(2, "hashed-api-key");
            statement.setInt(3, 16);
            statement.setString(4, hostname);
            statement.setString(5, "192.168.1.10");
            statement.setString(6, "Linux");
            statement.setInt(7, 8192);
            statement.setString(8, "APPROVED");
            statement.setString(9, "ONLINE");
            statement.setString(10, "AVAILABLE");
            statement.setInt(11, 32768);
            statement.executeUpdate();
        }
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
            statement.setString(2, "v6-migration-user-" + UUID.randomUUID());
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
            statement.setString(2, "localhive.v6-migration-" + UUID.randomUUID());
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
            statement.setString(4, "V6 Migration Definition");
            statement.setString(5, "localhive.v6-executor");
            statement.setInt(6, 1);
            statement.setInt(7, 0);
            statement.setInt(8, 0);
            statement.setBoolean(9, false);
            statement.setString(10, "0".repeat(64));
            statement.setString(11, "APPROVED");
            statement.setObject(12, userId);
            statement.setObject(13, userId);
            statement.executeUpdate();
        }
    }

    private static void insertAssignedExecution(Connection connection,
                                                UUID executionId,
                                                UUID versionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    instance_id,
                    status,
                    created_at,
                    queued_at,
                    assigned_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setNull(3, Types.OTHER);
            statement.setString(4, "ASSIGNED");
            statement.setInt(5, 0);
            statement.setInt(6, 0);
            statement.setBoolean(7, false);
            statement.executeUpdate();
        }
    }

    private static void insertAssignment(Connection connection,
                                         UUID assignmentId,
                                         UUID executionId,
                                         UUID workerId,
                                         String assignmentMode) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into execution_assignments (
                    id,
                    execution_id,
                    worker_id,
                    assignment_mode,
                    assigned_at
                ) values (?, ?, ?, ?, current_timestamp)
                """)) {
            statement.setObject(1, assignmentId);
            statement.setObject(2, executionId);
            statement.setObject(3, workerId);
            statement.setString(4, assignmentMode);
            statement.executeUpdate();
        }
    }

    private static void insertRunningAttempt(Connection connection,
                                             UUID attemptId,
                                             UUID executionId,
                                             UUID assignmentId,
                                             int attemptNumber) throws SQLException {
        insertAttemptWithStatus(
                connection,
                attemptId,
                executionId,
                assignmentId,
                "RUNNING",
                null,
                null,
                null,
                attemptNumber
        );
    }

    private static void insertAttemptWithStatus(Connection connection,
                                                UUID attemptId,
                                                UUID executionId,
                                                UUID assignmentId,
                                                String status,
                                                LocalDateTime completedAt,
                                                String failureCode,
                                                String failureMessage) throws SQLException {
        insertAttemptWithStatus(
                connection,
                attemptId,
                executionId,
                assignmentId,
                status,
                completedAt,
                failureCode,
                failureMessage,
                1
        );
    }

    private static void insertAttemptWithStatus(Connection connection,
                                                UUID attemptId,
                                                UUID executionId,
                                                UUID assignmentId,
                                                String status,
                                                LocalDateTime completedAt,
                                                String failureCode,
                                                String failureMessage,
                                                int attemptNumber) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into execution_attempts (
                    id,
                    execution_id,
                    assignment_id,
                    attempt_number,
                    status,
                    started_at,
                    completed_at,
                    failure_code,
                    failure_message
                ) values (?, ?, ?, ?, ?, current_timestamp, ?, ?, ?)
                """)) {
            statement.setObject(1, attemptId);
            statement.setObject(2, executionId);
            statement.setObject(3, assignmentId);
            statement.setInt(4, attemptNumber);
            statement.setString(5, status);
            if (completedAt == null) {
                statement.setNull(6, Types.TIMESTAMP);
            } else {
                statement.setObject(6, completedAt);
            }
            statement.setString(7, failureCode);
            statement.setString(8, failureMessage);
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

    private static List<String> uniqueConstraints(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select constraint_name
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = ?
                  and constraint_type = 'UNIQUE'
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
