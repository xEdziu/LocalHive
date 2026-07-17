package dev.adrian.goral.localhivebackend.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateInstanceExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.CreateWorkInstanceCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import dev.adrian.goral.localhivebackend.service.work.WorkInstanceManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkExecutionPersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkInstanceManagementService instanceManagementService;

    @Autowired
    private WorkExecutionCreationService executionCreationService;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistOneOffAndInstanceExecutionsWithJsonAndResourceSnapshots() {
        UUID adminUserId = createUser("execution-persistence-admin").getId();
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("stringValue", "value");
        configuration.put("integerValue", 42);
        configuration.put("booleanValue", true);
        configuration.putArray("arrayValue").add("a").add("b");
        configuration.set("explicitNull", JsonNodeFactory.instance.nullNode());
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.execution-persistence-" + UUID.randomUUID(),
                configuration,
                ResourceRequest.of(1024, 2, false),
                adminUserId
        ));
        WorkInstance instance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Persistent Instance",
                JsonNodeFactory.instance.objectNode().put("integerValue", 100),
                ResourceRequestOverrides.of(null, 4, true)
        ));

        WorkExecution oneOffExecution = executionCreationService.createOneOffExecution(
                new dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand(
                        version.getId(),
                        null,
                        null
                )
        );
        WorkExecution instanceExecution = executionCreationService.createInstanceExecution(
                new CreateInstanceExecutionCommand(instance.getId())
        );

        assertThat(oneOffExecution.getInstance()).isNull();
        assertThat(instanceExecution.getInstance().getId()).isEqualTo(instance.getId());
        assertThat(executionRepository.findByDefinitionVersion(version)).hasSize(2);
        assertThat(executionRepository.findByInstance(instance)).hasSize(1);
        assertThat(executionRepository.findByStatus(WorkExecutionStatus.QUEUED)).hasSizeGreaterThanOrEqualTo(2);

        assertThat(executionRepository.findById(oneOffExecution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getResolvedConfigurationSnapshot().get("stringValue").textValue()).isEqualTo("value");
                    assertThat(stored.getResolvedConfigurationSnapshot().get("integerValue").intValue()).isEqualTo(42);
                    assertThat(stored.getResolvedConfigurationSnapshot().get("booleanValue").booleanValue()).isTrue();
                    assertThat(stored.getResolvedConfigurationSnapshot().get("arrayValue").size()).isEqualTo(2);
                    assertThat(stored.getResolvedConfigurationSnapshot().get("explicitNull").isNull()).isTrue();
                    assertThat(stored.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(1024, 2, false));
                });
        assertThat(executionRepository.findById(instanceExecution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getResolvedConfigurationSnapshot().get("integerValue").intValue()).isEqualTo(100);
                    assertThat(stored.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(1024, 4, true));
                });
    }

    @Test
    void shouldEnforceDatabaseConstraints() {
        UUID adminUserId = createUser("execution-constraint-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.execution-constraint-" + UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));

        assertThatThrownBy(() -> jdbcTemplate.update("""
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
                UUID.randomUUID(),
                version.getId(),
                "CREATED",
                0,
                0,
                false
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
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
                ) values (?, ?, ?, current_timestamp, current_timestamp, '[]'::jsonb, ?, ?, ?)
                """,
                UUID.randomUUID(),
                version.getId(),
                "QUEUED",
                0,
                0,
                false
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
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
                UUID.randomUUID(),
                version.getId(),
                "QUEUED",
                -1,
                0,
                false
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
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
                """,
                UUID.randomUUID(),
                version.getId(),
                UUID.randomUUID(),
                "QUEUED",
                0,
                0,
                false
        )).isInstanceOf(DataIntegrityViolationException.class);

    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static DefinitionContentCommand command(String logicalIdentifier,
                                                    ObjectNode executorConfiguration,
                                                    ResourceRequest defaultResourceRequest,
                                                    UUID actorUserId) {
        return new DefinitionContentCommand(
                logicalIdentifier,
                WorkType.TASK,
                "Persistence Definition",
                null,
                "localhive.persistence-executor",
                1,
                executorConfiguration,
                defaultResourceRequest,
                actorUserId
        );
    }
}
