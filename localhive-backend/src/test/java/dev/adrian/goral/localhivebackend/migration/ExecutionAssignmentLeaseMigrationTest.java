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
class ExecutionAssignmentLeaseMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    static final PostgreSQLContainer freshPostgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Container
    static final PostgreSQLContainer historicalPostgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Test
    @DisplayName("V7 adds lease fields on fresh migration chain")
    void shouldMigrateFreshSchemaToV7WithLeaseFields() throws SQLException {
        Flyway flyway = flyway(freshPostgres, null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");

        try (Connection connection = connection(freshPostgres)) {
            assertThat(columns(connection, "execution_assignments"))
                    .contains("claimed_at", "lease_expires_at", "lease_token_hash")
                    .doesNotContain("lease_token", "raw_lease_token");
            assertThat(checkConstraints(connection, "execution_assignments"))
                    .contains("execution_assignments_lease_fields_check");
            assertThat(tables(connection))
                    .doesNotContain("execution_leases", "worker_polling", "leases");
        }
    }

    @Test
    @DisplayName("V7 migrates historical V6 assignments with null lease fields and constraints")
    void shouldMigrateHistoricalV6AssignmentsToV7() throws SQLException {
        flyway(historicalPostgres, "6").migrate();

        UUID workerId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        try (Connection connection = connection(historicalPostgres)) {
            insertWorker(connection, workerId);
            insertApprovedWorkDefinitionVersion(connection, versionId);
            insertAssignedExecution(connection, executionId, versionId);
            insertAssignment(connection, assignmentId, executionId, workerId);
        }

        Flyway flyway = flyway(historicalPostgres, null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");

        try (Connection connection = connection(historicalPostgres)) {
            assertThat(leaseFields(connection, assignmentId))
                    .containsExactly(null, null, null);

            assertThatThrownBy(() -> updateOnlyLeaseTokenHash(connection, assignmentId))
                    .isInstanceOf(SQLException.class);

            updateCompleteLease(connection, assignmentId);

            assertThat(leaseFields(connection, assignmentId))
                    .containsExactly(
                            LocalDateTime.parse("2026-07-17T10:01:00"),
                            LocalDateTime.parse("2026-07-17T10:02:00"),
                            "hashed-lease-token"
                    );
        }
    }

    private static Flyway flyway(PostgreSQLContainer container, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");

        if (targetVersion != null) {
            configuration.target(targetVersion);
        }

        return configuration.load();
    }

    private static Connection connection(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
    }

    private static void insertWorker(Connection connection, UUID workerId) throws SQLException {
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
            statement.setString(4, "v7-worker-" + UUID.randomUUID());
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
            statement.setString(2, "v7-migration-user-" + UUID.randomUUID());
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
            statement.setString(2, "localhive.v7-migration-" + UUID.randomUUID());
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
            statement.setString(4, "V7 Migration Definition");
            statement.setString(5, "localhive.v7-executor");
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

    private static void insertAssignedExecution(Connection connection, UUID executionId, UUID versionId) throws SQLException {
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
                                         UUID workerId) throws SQLException {
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
            statement.setString(4, "AUTO");
            statement.executeUpdate();
        }
    }

    private static void updateOnlyLeaseTokenHash(Connection connection, UUID assignmentId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update execution_assignments
                set lease_token_hash = ?
                where id = ?
                """)) {
            statement.setString(1, "hashed-lease-token");
            statement.setObject(2, assignmentId);
            statement.executeUpdate();
        }
    }

    private static void updateCompleteLease(Connection connection, UUID assignmentId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update execution_assignments
                set claimed_at = ?,
                    lease_expires_at = ?,
                    lease_token_hash = ?
                where id = ?
                """)) {
            statement.setObject(1, LocalDateTime.parse("2026-07-17T10:01:00"));
            statement.setObject(2, LocalDateTime.parse("2026-07-17T10:02:00"));
            statement.setString(3, "hashed-lease-token");
            statement.setObject(4, assignmentId);
            statement.executeUpdate();
        }
    }

    private static List<Object> leaseFields(Connection connection, UUID assignmentId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select claimed_at, lease_expires_at, lease_token_hash
                from execution_assignments
                where id = ?
                """)) {
            statement.setObject(1, assignmentId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                List<Object> fields = new ArrayList<>();
                fields.add(resultSet.getObject("claimed_at", LocalDateTime.class));
                fields.add(resultSet.getObject("lease_expires_at", LocalDateTime.class));
                fields.add(resultSet.getString("lease_token_hash"));
                return fields;
            }
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
}
