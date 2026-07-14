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

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        try (var connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();
            assertThat(jdbcUrl)
                    .startsWith("jdbc:postgresql://")
                    .doesNotContain("localhost:5432");
        }

        Integer appliedBaselineCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class
        );
        assertThat(appliedBaselineCount).isEqualTo(1);

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
                "server_instances"
        );
    }
}
