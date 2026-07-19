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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkerStatusMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Test
    @DisplayName("V2 deterministically migrates historical worker status values through the current migration chain")
    void shouldMigrateHistoricalWorkerStatusValues() throws SQLException {
        flyway("1").migrate();

        try (Connection connection = connection()) {
            insertWorker(connection, "pending-worker", "PENDING");
            insertWorker(connection, "active-worker", "ACTIVE");
            insertWorker(connection, "paused-worker", "PAUSED");
            insertWorker(connection, "offline-worker", "OFFLINE");
        }

        Flyway flyway = flyway(null);
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");

        try (Connection connection = connection()) {
            assertWorkerState(connection, "pending-worker", "PENDING", "OFFLINE", "AVAILABLE");
            assertWorkerState(connection, "active-worker", "APPROVED", "ONLINE", "AVAILABLE");
            assertWorkerState(connection, "paused-worker", "APPROVED", "ONLINE", "PAUSED");
            assertWorkerState(connection, "offline-worker", "APPROVED", "OFFLINE", "AVAILABLE");

            assertThat(workerColumns(connection))
                    .contains("approval_status", "connection_status", "availability_status")
                    .doesNotContain("status");
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

    private static void insertWorker(Connection connection, String hostname, String status) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into workers (
                    id,
                    cpu_cores,
                    hostname,
                    ip_address,
                    os_type,
                    shared_ram_mb,
                    status,
                    total_ram_mb
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setInt(2, 16);
            statement.setString(3, hostname);
            statement.setString(4, "192.168.1.10");
            statement.setString(5, "Windows 11");
            statement.setInt(6, 4096);
            statement.setString(7, status);
            statement.setInt(8, 32768);
            statement.executeUpdate();
        }
    }

    private static void assertWorkerState(Connection connection,
                                          String hostname,
                                          String approvalStatus,
                                          String connectionStatus,
                                          String availabilityStatus) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select approval_status, connection_status, availability_status
                from workers
                where hostname = ?
                """)) {
            statement.setString(1, hostname);

            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("approval_status")).isEqualTo(approvalStatus);
                assertThat(resultSet.getString("connection_status")).isEqualTo(connectionStatus);
                assertThat(resultSet.getString("availability_status")).isEqualTo(availabilityStatus);
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    private static List<String> workerColumns(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'workers'
        """)) {
            try (var resultSet = statement.executeQuery()) {
                java.util.List<String> columns = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("column_name"));
                }
                return columns;
            }
        }
    }
}
