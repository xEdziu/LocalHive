package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkInstanceManagementServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkInstanceManagementService instanceManagementService;

    @Autowired
    private WorkInstanceRepository instanceRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldPersistInstancesResolveEffectiveConfigurationAndAllowDuplicateDisplayNames() {
        UUID adminUserId = createUser("instance-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.instance-prime",
                "Prime Definition",
                baseConfiguration(),
                ResourceRequest.of(2048, 2, false),
                adminUserId
        ));

        WorkInstance firstInstance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Prime Runner",
                overridesConfiguration(),
                ResourceRequestOverrides.of(null, 4, true)
        ));
        WorkInstance secondInstance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Prime Runner",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ));

        assertThat(firstInstance.getId()).isNotNull();
        assertThat(firstInstance.isEnabled()).isTrue();
        assertThat(firstInstance.getCreatedAt()).isEqualTo(firstInstance.getUpdatedAt());
        assertThat(secondInstance.getId()).isNotNull();
        assertThat(instanceRepository.findByDefinitionVersion(version)).hasSize(2);
        assertThat(instanceRepository.findByDefinitionVersionAndEnabledTrue(version)).hasSize(2);
        assertThat(instanceRepository.findById(firstInstance.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getConfigurationOverrides().at("/limits/threads").intValue()).isEqualTo(4);
                    assertThat(stored.getResourceOverrides()).isEqualTo(ResourceRequestOverrides.of(null, 4, true));
                });

        JsonNode resolvedConfiguration = instanceManagementService.resolveConfiguration(firstInstance.getId());
        assertThat(resolvedConfiguration.at("/limits/threads").intValue()).isEqualTo(4);
        assertThat(resolvedConfiguration.at("/limits/batch").intValue()).isEqualTo(100);
        assertThat(resolvedConfiguration.get("array").size()).isEqualTo(1);
        assertThat(resolvedConfiguration.get("array").get(0).intValue()).isEqualTo(3);
        assertThat(resolvedConfiguration.get("explicitNull").isNull()).isTrue();

        ResourceRequest resolvedResources = instanceManagementService.resolveResourceRequest(firstInstance.getId());
        assertThat(resolvedResources).isEqualTo(ResourceRequest.of(2048, 4, true));
    }

    @Test
    void shouldRenameDisableAndEnableInstance() {
        UUID adminUserId = createUser("mutate-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.instance-mutate",
                "Mutable Instance",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));
        WorkInstance instance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Before",
                JsonNodeFactory.instance.objectNode(),
                null
        ));

        WorkInstance renamed = instanceManagementService.renameInstance(instance.getId(), "After");
        assertThat(renamed.getDisplayName()).isEqualTo("After");
        assertThat(instanceRepository.findById(instance.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getDisplayName()).isEqualTo("After");
                    assertThat(toDatabaseTimestampPrecision(stored.getUpdatedAt()))
                            .isAfterOrEqualTo(toDatabaseTimestampPrecision(stored.getCreatedAt()));
                });

        WorkInstance disabled = instanceManagementService.disableInstance(instance.getId());
        assertThat(disabled.isEnabled()).isFalse();

        WorkInstance enabled = instanceManagementService.enableInstance(instance.getId());
        assertThat(enabled.isEnabled()).isTrue();
    }

    @Test
    void shouldUpgradeInstanceOnlyToVersionFromSameDefinition() {
        UUID adminUserId = createUser("upgrade-admin").getId();
        WorkDefinitionVersion firstVersion = definitionManagementService.createLocalDefinition(command(
                "localhive.instance-upgrade",
                "Upgrade v1",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));
        WorkDefinitionVersion secondVersion = definitionManagementService.addLocalVersion(command(
                "localhive.instance-upgrade",
                "Upgrade v2",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.of(512, 1, false),
                adminUserId
        ));
        WorkDefinitionVersion otherDefinitionVersion = definitionManagementService.createLocalDefinition(command(
                "localhive.instance-other",
                "Other",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));
        WorkInstance instance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                firstVersion.getId(),
                "Upgradeable",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ));

        WorkInstance upgraded = instanceManagementService.upgradeInstanceDefinitionVersion(
                instance.getId(),
                secondVersion.getId()
        );
        assertThat(upgraded.getDefinitionVersion().getId()).isEqualTo(secondVersion.getId());

        assertThatThrownBy(() -> instanceManagementService.upgradeInstanceDefinitionVersion(
                instance.getId(),
                otherDefinitionVersion.getId()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(instanceRepository.findById(instance.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getDefinitionVersion().getId())
                        .isEqualTo(secondVersion.getId()));
    }

    @Test
    void shouldRejectUnknownDefinitionVersionAndInvalidOverrides() {
        assertThatThrownBy(() -> instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                UUID.randomUUID(),
                "Missing Version",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty()
        ))).isInstanceOf(IllegalArgumentException.class);

        UUID adminUserId = createUser("invalid-instance-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.instance-invalid-config",
                "Invalid Config",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));

        assertThatThrownBy(() -> instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Invalid Config",
                JsonNodeFactory.instance.arrayNode(),
                ResourceRequestOverrides.empty()
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static LocalDateTime toDatabaseTimestampPrecision(LocalDateTime timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }

    private static DefinitionContentCommand command(String logicalIdentifier,
                                                    String name,
                                                    JsonNode executorConfiguration,
                                                    ResourceRequest defaultResourceRequest,
                                                    UUID actorUserId) {
        return new DefinitionContentCommand(
                logicalIdentifier,
                WorkType.TASK,
                name,
                null,
                "localhive.instance-executor",
                1,
                executorConfiguration,
                defaultResourceRequest,
                actorUserId
        );
    }

    private static ObjectNode baseConfiguration() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode limits = JsonNodeFactory.instance.objectNode();
        limits.put("threads", 2);
        limits.put("batch", 100);
        root.set("limits", limits);
        root.putArray("array").add(1).add(2);
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
