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
class ExecutionDisplayNameMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Test
    @DisplayName("V10 adds and backfills execution display name snapshots")
    void shouldAddAndBackfillExecutionDisplayNameSnapshots() throws SQLException {
        flyway("9").migrate();

        UUID versionId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID oneOffExecutionId = UUID.randomUUID();
        UUID instanceExecutionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertApprovedWorkDefinitionVersion(connection, versionId);
            insertWorkInstance(connection, instanceId, versionId);
            insertQueuedExecution(connection, oneOffExecutionId, versionId, null);
            insertQueuedExecution(connection, instanceExecutionId, versionId, instanceId);
        }

        Flyway flyway = flyway(null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");

        try (Connection connection = connection()) {
            assertThat(columns(connection, "work_executions")).contains("display_name_snapshot");
            assertThat(checkConstraints(connection, "work_executions"))
                    .contains("work_executions_display_name_snapshot_check");
            assertDisplayNameColumn(connection);
            assertThat(displayNameSnapshot(connection, oneOffExecutionId)).isEqualTo("Migration Display Definition");
            assertThat(displayNameSnapshot(connection, instanceExecutionId)).isEqualTo("Migration Display Instance");
            assertThatThrownBy(() -> insertExecutionWithDisplayName(connection, UUID.randomUUID(), versionId, "   "))
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
            statement.setString(2, "display-name-migration-user-" + UUID.randomUUID());
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
            statement.setString(2, "localhive.display-name-migration-" + UUID.randomUUID());
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
            statement.setString(4, "Migration Display Definition");
            statement.setString(5, "localhive.display-name-executor");
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
            statement.setString(3, "Migration Display Instance");
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
                ) values (?, ?, ?, ?, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
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

    private static void insertExecutionWithDisplayName(Connection connection,
                                                       UUID executionId,
                                                       UUID versionId,
                                                       String displayName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    display_name_snapshot,
                    status,
                    created_at,
                    queued_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, ?, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, displayName);
            statement.setString(4, "QUEUED");
            statement.setInt(5, 128);
            statement.setInt(6, 1);
            statement.setBoolean(7, false);
            statement.executeUpdate();
        }
    }

    private static String displayNameSnapshot(Connection connection, UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select display_name_snapshot
                from work_executions
                where id = ?
                """)) {
            statement.setObject(1, executionId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static void assertDisplayNameColumn(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select is_nullable, character_maximum_length
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'work_executions'
                  and column_name = 'display_name_snapshot'
                """)) {
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("is_nullable")).isEqualTo("NO");
                assertThat(resultSet.getInt("character_maximum_length")).isEqualTo(255);
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
