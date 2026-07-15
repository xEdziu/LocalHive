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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");

        try (var connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();
            assertThat(jdbcUrl)
                    .startsWith("jdbc:postgresql://")
                    .doesNotContain("localhost:5432");
        }

        Integer appliedMigrationCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version in ('1', '2', '3') and success = true",
                Integer.class
        );
        assertThat(appliedMigrationCount).isEqualTo(3);

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
                "work_definition_versions"
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
}
