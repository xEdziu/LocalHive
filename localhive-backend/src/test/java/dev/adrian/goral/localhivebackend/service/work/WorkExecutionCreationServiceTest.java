package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
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
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkExecutionCreationServiceTest {

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

    @Test
    void shouldCreateOneOffExecutionFromApprovedDefinitionWithOverrides() {
        UUID adminUserId = createUser("one-off-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                logicalIdentifier("one-off"),
                baseConfiguration(),
                ResourceRequest.of(1024, 2, false),
                adminUserId
        ));

        WorkExecution execution = executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                overridesConfiguration(),
                ResourceRequestOverrides.of(2048, null, true)
        ));

        assertThat(execution.getId()).isNotNull();
        assertThat(execution.getInstance()).isNull();
        assertThat(execution.getDefinitionVersion().getId()).isEqualTo(version.getId());
        assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
        assertThat(execution.getCreatedAt()).isEqualTo(execution.getQueuedAt());
        assertThat(execution.getResolvedConfigurationSnapshot().at("/limits/threads").intValue()).isEqualTo(4);
        assertThat(execution.getResolvedConfigurationSnapshot().at("/limits/batch").intValue()).isEqualTo(100);
        assertThat(execution.getResolvedConfigurationSnapshot().get("array").get(0).intValue()).isEqualTo(3);
        assertThat(execution.getResolvedConfigurationSnapshot().get("explicitNull").isNull()).isTrue();
        assertThat(execution.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(2048, 2, true));
    }

    @Test
    void shouldCreateOneOffExecutionWithNullOverrides() {
        UUID adminUserId = createUser("one-off-null-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                logicalIdentifier("one-off-null"),
                baseConfiguration(),
                ResourceRequest.of(512, 1, false),
                adminUserId
        ));

        WorkExecution execution = executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                null,
                null
        ));

        assertThat(execution.getResolvedConfigurationSnapshot().at("/limits/threads").intValue()).isEqualTo(2);
        assertThat(execution.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(512, 1, false));
    }

    @Test
    void shouldRejectOneOffExecutionForMissingPendingAndRejectedDefinitionVersions() {
        UUID importerUserId = createUser("one-off-importer").getId();
        UUID reviewerUserId = createUser("one-off-reviewer").getId();
        WorkDefinitionVersion pendingVersion = definitionManagementService.createImportedDefinition(command(
                logicalIdentifier("pending-one-off"),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                importerUserId
        ), "pending-one-off");
        WorkDefinitionVersion rejectedVersion = definitionManagementService.createImportedDefinition(command(
                logicalIdentifier("rejected-one-off"),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                importerUserId
        ), "rejected-one-off");
        definitionManagementService.rejectImportedPendingVersion(
                rejectedVersion.getDefinition().getLogicalIdentifier(),
                rejectedVersion.getVersionNumber(),
                reviewerUserId
        );

        assertThatThrownBy(() -> executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                pendingVersion.getId(),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                rejectedVersion.getId(),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCreateInstanceExecutionFromEnabledInstance() {
        UUID adminUserId = createUser("instance-execution-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                logicalIdentifier("instance-execution"),
                baseConfiguration(),
                ResourceRequest.of(1024, 2, false),
                adminUserId
        ));
        WorkInstance instance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Execution Instance",
                overridesConfiguration(),
                ResourceRequestOverrides.of(null, 4, true)
        ));

        WorkExecution execution = executionCreationService.createInstanceExecution(new CreateInstanceExecutionCommand(
                instance.getId()
        ));

        assertThat(execution.getInstance().getId()).isEqualTo(instance.getId());
        assertThat(execution.getDefinitionVersion().getId()).isEqualTo(version.getId());
        assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
        assertThat(execution.getResolvedConfigurationSnapshot().at("/limits/threads").intValue()).isEqualTo(4);
        assertThat(execution.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(1024, 4, true));
        assertThat(executionRepository.findByStatus(WorkExecutionStatus.QUEUED)).isNotEmpty();
    }

    @Test
    void shouldRejectInstanceExecutionForDisabledMissingPendingAndRejectedInstances() {
        UUID importerUserId = createUser("instance-importer").getId();
        UUID reviewerUserId = createUser("instance-reviewer").getId();
        WorkDefinitionVersion approvedVersion = definitionManagementService.createLocalDefinition(command(
                logicalIdentifier("disabled-instance-execution"),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                importerUserId
        ));
        WorkInstance disabledInstance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                approvedVersion.getId(),
                "Disabled",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ));
        instanceManagementService.disableInstance(disabledInstance.getId());

        WorkDefinitionVersion pendingVersion = definitionManagementService.createImportedDefinition(command(
                logicalIdentifier("pending-instance-execution"),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                importerUserId
        ), "pending-instance-execution");
        WorkInstance pendingInstance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                pendingVersion.getId(),
                "Pending",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ));

        WorkDefinitionVersion rejectedVersion = definitionManagementService.createImportedDefinition(command(
                logicalIdentifier("rejected-instance-execution"),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                importerUserId
        ), "rejected-instance-execution");
        WorkInstance rejectedInstance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                rejectedVersion.getId(),
                "Rejected",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ));
        definitionManagementService.rejectImportedPendingVersion(
                rejectedVersion.getDefinition().getLogicalIdentifier(),
                rejectedVersion.getVersionNumber(),
                reviewerUserId
        );

        assertThatThrownBy(() -> executionCreationService.createInstanceExecution(new CreateInstanceExecutionCommand(
                UUID.randomUUID()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executionCreationService.createInstanceExecution(new CreateInstanceExecutionCommand(
                disabledInstance.getId()
        ))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executionCreationService.createInstanceExecution(new CreateInstanceExecutionCommand(
                pendingInstance.getId()
        ))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executionCreationService.createInstanceExecution(new CreateInstanceExecutionCommand(
                rejectedInstance.getId()
        ))).isInstanceOf(IllegalStateException.class);
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static DefinitionContentCommand command(String logicalIdentifier,
                                                    JsonNode executorConfiguration,
                                                    ResourceRequest defaultResourceRequest,
                                                    UUID actorUserId) {
        return new DefinitionContentCommand(
                logicalIdentifier,
                WorkType.TASK,
                "Execution Definition",
                null,
                "localhive.execution-executor",
                1,
                executorConfiguration,
                defaultResourceRequest,
                actorUserId
        );
    }

    private static String logicalIdentifier(String suffix) {
        return "localhive." + suffix + "-" + UUID.randomUUID();
    }

    private static ObjectNode baseConfiguration() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode limits = JsonNodeFactory.instance.objectNode();
        limits.put("threads", 2);
        limits.put("batch", 100);
        root.set("limits", limits);
        root.putArray("array").add(1).add(2);
        root.put("mode", "base");
        return root;
    }

    private static ObjectNode overridesConfiguration() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode limits = JsonNodeFactory.instance.objectNode();
        limits.put("threads", 4);
        root.set("limits", limits);
        root.putArray("array").add(3);
        root.set("explicitNull", JsonNodeFactory.instance.nullNode());
        return root;
    }
}
