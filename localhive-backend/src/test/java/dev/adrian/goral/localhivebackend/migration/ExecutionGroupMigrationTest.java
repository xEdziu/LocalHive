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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ExecutionGroupMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Test
    @DisplayName("V13/V14 add execution groups, nullable WorkExecution group metadata, and merge plans")
    void shouldAddExecutionGroupsAndNullableExecutionMetadata() throws SQLException {
        flyway("12").migrate();

        UUID versionId = UUID.randomUUID();
        UUID standaloneExecutionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertApprovedWorkDefinitionVersion(connection, versionId);
            insertQueuedExecution(connection, standaloneExecutionId, versionId);
        }

        Flyway flyway = flyway(null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");

        try (Connection connection = connection()) {
            assertThat(tables(connection)).contains("execution_groups");
            assertThat(columns(connection, "execution_groups"))
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
            assertThat(checkConstraints(connection, "execution_groups"))
                    .contains(
                            "execution_groups_display_name_check",
                            "execution_groups_status_check",
                            "execution_groups_merge_mode_check",
                            "execution_groups_failure_policy_check",
                            "execution_groups_shard_count_check",
                            "execution_groups_failure_fields_check"
                    );
            assertThat(indexes(connection, "execution_groups"))
                    .contains("idx_execution_groups_status", "idx_execution_groups_created_at");

            assertThat(columns(connection, "work_executions"))
                    .contains("execution_group_id", "group_role", "shard_index", "shard_count");
            assertThat(foreignKeys(connection, "work_executions"))
                    .contains("fk_work_executions_execution_group_id");
            assertThat(checkConstraints(connection, "work_executions"))
                    .contains(
                            "work_executions_group_role_check",
                            "work_executions_group_metadata_check"
                    );
            assertThat(indexes(connection, "work_executions"))
                    .contains(
                            "idx_work_executions_execution_group_id",
                            "idx_work_executions_execution_group_role_shard_index"
                    );
            assertThat(tables(connection)).contains("execution_group_merge_plans");
            assertThat(columns(connection, "execution_group_merge_plans"))
                    .contains(
                            "execution_group_id",
                            "definition_version_id",
                            "configuration_template",
                            "created_at"
                    );
            assertThat(foreignKeys(connection, "execution_group_merge_plans"))
                    .contains(
                            "fk_execution_group_merge_plans_execution_group_id",
                            "fk_execution_group_merge_plans_definition_version_id"
                    );
            assertThat(checkConstraints(connection, "execution_group_merge_plans"))
                    .contains("execution_group_merge_plans_configuration_template_object_check");
            assertThat(groupMetadata(connection, standaloneExecutionId))
                    .containsExactly(null, null, null, null);

            UUID executionGroupId = UUID.randomUUID();
            insertExecutionGroup(connection, executionGroupId, 2);
            insertShardExecution(connection, UUID.randomUUID(), versionId, executionGroupId, 0, 2);
            insertMergePlan(connection, executionGroupId, versionId, "'{\"commandTemplate\":[\"sh\"]}'::jsonb");

            assertThatThrownBy(() -> insertExecutionGroup(connection, UUID.randomUUID(), 0))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertInvalidShardExecution(
                    connection,
                    UUID.randomUUID(),
                    versionId,
                    executionGroupId,
                    2,
                    2
            )).isInstanceOf(SQLException.class);
            UUID invalidMergePlanGroupId = UUID.randomUUID();
            insertExecutionGroup(connection, invalidMergePlanGroupId, 2);
            assertThatThrownBy(() -> insertMergePlan(
                    connection,
                    invalidMergePlanGroupId,
                    versionId,
                    "'[]'::jsonb"
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
            statement.setString(2, "execution-group-migration-user-" + UUID.randomUUID());
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
            statement.setString(2, "localhive.execution-group-migration-" + UUID.randomUUID());
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
            statement.setString(4, "Execution Group Migration Definition");
            statement.setString(5, "localhive.execution-group-executor");
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

    private static void insertQueuedExecution(Connection connection,
                                              UUID executionId,
                                              UUID versionId) throws SQLException {
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
            statement.setString(3, "Standalone execution");
            statement.setString(4, "QUEUED");
            statement.setInt(5, 0);
            statement.setInt(6, 0);
            statement.setBoolean(7, false);
            statement.executeUpdate();
        }
    }

    private static void insertExecutionGroup(Connection connection, UUID executionGroupId, int shardCount) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into execution_groups (
                    id,
                    display_name,
                    status,
                    merge_mode,
                    failure_policy,
                    shard_count,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                """)) {
            statement.setObject(1, executionGroupId);
            statement.setString(2, "Migration group");
            statement.setString(3, "CREATED");
            statement.setString(4, "NONE");
            statement.setString(5, "FAIL_FAST");
            statement.setInt(6, shardCount);
            statement.executeUpdate();
        }
    }

    private static void insertShardExecution(Connection connection,
                                             UUID executionId,
                                             UUID versionId,
                                             UUID executionGroupId,
                                             int shardIndex,
                                             int shardCount) throws SQLException {
        insertGroupedExecution(connection, executionId, versionId, executionGroupId, "SHARD", shardIndex, shardCount);
    }

    private static void insertInvalidShardExecution(Connection connection,
                                                    UUID executionId,
                                                    UUID versionId,
                                                    UUID executionGroupId,
                                                    int shardIndex,
                                                    int shardCount) throws SQLException {
        insertGroupedExecution(connection, executionId, versionId, executionGroupId, "SHARD", shardIndex, shardCount);
    }

    private static void insertMergePlan(Connection connection,
                                        UUID executionGroupId,
                                        UUID versionId,
                                        String configurationTemplateSql) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into execution_group_merge_plans (
                    execution_group_id,
                    definition_version_id,
                    configuration_template,
                    created_at
                ) values (?, ?, %s, current_timestamp)
                """.formatted(configurationTemplateSql))) {
            statement.setObject(1, executionGroupId);
            statement.setObject(2, versionId);
            statement.executeUpdate();
        }
    }

    private static void insertGroupedExecution(Connection connection,
                                               UUID executionId,
                                               UUID versionId,
                                               UUID executionGroupId,
                                               String groupRole,
                                               int shardIndex,
                                               int shardCount) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into work_executions (
                    id,
                    definition_version_id,
                    display_name_snapshot,
                    execution_group_id,
                    group_role,
                    shard_index,
                    shard_count,
                    status,
                    created_at,
                    queued_at,
                    resolved_configuration_snapshot,
                    resolved_required_ram_mb,
                    resolved_required_cpu_cores,
                    resolved_gpu_required
                ) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, '{}'::jsonb, ?, ?, ?)
                """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, versionId);
            statement.setString(3, "Shard execution");
            statement.setObject(4, executionGroupId);
            statement.setString(5, groupRole);
            statement.setInt(6, shardIndex);
            statement.setInt(7, shardCount);
            statement.setString(8, "QUEUED");
            statement.setInt(9, 0);
            statement.setInt(10, 0);
            statement.setBoolean(11, false);
            statement.executeUpdate();
        }
    }

    private static List<Object> groupMetadata(Connection connection, UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select execution_group_id, group_role, shard_index, shard_count
                from work_executions
                where id = ?
                """)) {
            statement.setObject(1, executionId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                List<Object> fields = new ArrayList<>();
                fields.add(resultSet.getObject("execution_group_id"));
                fields.add(resultSet.getString("group_role"));
                int shardIndex = resultSet.getInt("shard_index");
                fields.add(resultSet.wasNull() ? null : shardIndex);
                int shardCount = resultSet.getInt("shard_count");
                fields.add(resultSet.wasNull() ? null : shardCount);
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

    private static List<String> indexes(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select indexname
                from pg_indexes
                where schemaname = 'public'
                  and tablename = ?
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                List<String> indexes = new ArrayList<>();
                while (resultSet.next()) {
                    indexes.add(resultSet.getString("indexname"));
                }
                return indexes;
            }
        }
    }
}
