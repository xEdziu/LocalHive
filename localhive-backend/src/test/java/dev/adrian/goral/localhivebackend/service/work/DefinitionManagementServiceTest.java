package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionSourceType;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.exception.DuplicateResourceException;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class DefinitionManagementServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private DefinitionContentChecksumService checksumService;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldCreateLocalDefinitionWithApprovedFirstVersion() {
        UUID adminUserId = createUser("local-admin").getId();

        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.no-op-local",
                WorkType.TASK,
                "Local No-op",
                "Local definition",
                "localhive.no-op",
                1,
                config("timeoutSeconds", 30),
                adminUserId
        ));

        WorkDefinition definition = version.getDefinition();
        assertThat(definition.getLogicalIdentifier()).isEqualTo("localhive.no-op-local");
        assertThat(definition.getWorkType()).isEqualTo(WorkType.TASK);
        assertThat(definition.getSourceType()).isEqualTo(DefinitionSourceType.LOCAL);
        assertThat(definition.getOriginalDefinitionId()).isNull();

        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getApprovalStatus()).isEqualTo(DefinitionApprovalStatus.APPROVED);
        assertThat(version.getCreatedByUserId()).isEqualTo(adminUserId);
        assertThat(version.getImportedAt()).isNull();
        assertThat(version.getReviewedAt()).isEqualTo(version.getCreatedAt());
        assertThat(version.getReviewedByUserId()).isEqualTo(adminUserId);
        assertThat(version.getContentChecksum()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void shouldCreateImportedDefinitionWithPendingFirstVersion() {
        UUID importerUserId = createUser("importer").getId();

        WorkDefinitionVersion version = definitionManagementService.createImportedDefinition(command(
                "vendor.backup",
                WorkType.WORKLOAD,
                "Imported Backup",
                null,
                "vendor.backup",
                2,
                config("mode", "snapshot"),
                importerUserId
        ), "external-backup-v1");

        WorkDefinition definition = version.getDefinition();
        assertThat(definition.getLogicalIdentifier()).isEqualTo("vendor.backup");
        assertThat(definition.getWorkType()).isEqualTo(WorkType.WORKLOAD);
        assertThat(definition.getSourceType()).isEqualTo(DefinitionSourceType.IMPORTED);
        assertThat(definition.getOriginalDefinitionId()).isEqualTo("external-backup-v1");

        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getApprovalStatus()).isEqualTo(DefinitionApprovalStatus.PENDING);
        assertThat(version.getCreatedByUserId()).isEqualTo(importerUserId);
        assertThat(version.getImportedAt()).isEqualTo(version.getCreatedAt());
        assertThat(version.getReviewedAt()).isNull();
        assertThat(version.getReviewedByUserId()).isNull();
    }

    @Test
    void shouldAddSequentialLocalVersionsWithoutCallerControlledVersionNumber() {
        UUID adminUserId = createUser("version-admin").getId();
        definitionManagementService.createLocalDefinition(command(
                "localhive.versioned-task",
                WorkType.TASK,
                "Versioned Task",
                "v1",
                "localhive.versioned",
                1,
                config("step", 1),
                adminUserId
        ));

        WorkDefinitionVersion secondVersion = definitionManagementService.addLocalVersion(command(
                "localhive.versioned-task",
                WorkType.TASK,
                "Versioned Task",
                "v2",
                "localhive.versioned",
                1,
                config("step", 2),
                adminUserId
        ));

        assertThat(secondVersion.getVersionNumber()).isEqualTo(2);
        assertThat(versionRepository.findHighestVersionNumber(secondVersion.getDefinition())).isEqualTo(2);

        List<Integer> versionNumbers = versionRepository.findByDefinitionOrderByVersionNumberAsc(
                        secondVersion.getDefinition()
                )
                .stream()
                .map(WorkDefinitionVersion::getVersionNumber)
                .toList();
        assertThat(versionNumbers).containsExactly(1, 2);
    }

    @Test
    void shouldReviewImportedPendingVersionAsTerminalTransition() {
        UUID importerUserId = createUser("review-importer").getId();
        UUID reviewerUserId = createUser("reviewer").getId();
        WorkDefinitionVersion pendingVersion = definitionManagementService.createImportedDefinition(command(
                "vendor.reviewable",
                WorkType.TASK,
                "Reviewable",
                "needs review",
                "vendor.review",
                1,
                config("review", true),
                importerUserId
        ), "reviewable-v1");

        WorkDefinitionVersion approvedVersion = definitionManagementService.approveImportedPendingVersion(
                "vendor.reviewable",
                pendingVersion.getVersionNumber(),
                reviewerUserId
        );

        assertThat(approvedVersion.getApprovalStatus()).isEqualTo(DefinitionApprovalStatus.APPROVED);
        assertThat(approvedVersion.getReviewedAt()).isNotNull();
        assertThat(approvedVersion.getReviewedByUserId()).isEqualTo(reviewerUserId);

        assertThatThrownBy(() -> definitionManagementService.rejectImportedPendingVersion(
                "vendor.reviewable",
                pendingVersion.getVersionNumber(),
                reviewerUserId
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectImportedPendingVersion() {
        UUID importerUserId = createUser("reject-importer").getId();
        UUID reviewerUserId = createUser("reject-reviewer").getId();
        WorkDefinitionVersion pendingVersion = definitionManagementService.createImportedDefinition(command(
                "vendor.rejectable",
                WorkType.TASK,
                "Rejectable",
                "bad definition",
                "vendor.reject",
                1,
                config("review", false),
                importerUserId
        ), "rejectable-v1");

        WorkDefinitionVersion rejectedVersion = definitionManagementService.rejectImportedPendingVersion(
                "vendor.rejectable",
                pendingVersion.getVersionNumber(),
                reviewerUserId
        );

        assertThat(rejectedVersion.getApprovalStatus()).isEqualTo(DefinitionApprovalStatus.REJECTED);
        assertThat(rejectedVersion.getReviewedAt()).isNotNull();
        assertThat(rejectedVersion.getReviewedByUserId()).isEqualTo(reviewerUserId);
    }

    @Test
    void shouldRejectDuplicateLogicalIdentifier() {
        UUID adminUserId = createUser("duplicate-admin").getId();
        DefinitionContentCommand command = command(
                "localhive.unique",
                WorkType.TASK,
                "Unique",
                null,
                "localhive.unique",
                1,
                config("enabled", true),
                adminUserId
        );

        definitionManagementService.createLocalDefinition(command);

        assertThatThrownBy(() -> definitionManagementService.createLocalDefinition(command))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void shouldAcceptNamespacedLogicalIdentifierFormat() {
        UUID adminUserId = createUser("valid-logical-id-admin").getId();

        for (String validIdentifier : List.of(
                "localhive.no-op",
                "localhive.compute.prime-search",
                "adrian.custom-task"
        )) {
            WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                    validIdentifier,
                    WorkType.TASK,
                    "Valid",
                    null,
                    "localhive.valid-executor",
                    1,
                    config("enabled", true),
                    adminUserId
            ));

            assertThat(version.getDefinition().getLogicalIdentifier()).isEqualTo(validIdentifier);
        }
    }

    @Test
    void shouldValidateLogicalIdentifierFormat() {
        UUID adminUserId = createUser("validation-admin").getId();

        for (String invalidIdentifier : List.of(
                "minecraft",
                "prime-search",
                "localhive-no-op",
                ".localhive",
                "localhive.",
                "localhive..minecraft",
                "localhive.-minecraft",
                "localhive-.minecraft",
                "LocalHive.Minecraft",
                "localhive minecraft"
        )) {
            assertThatThrownBy(() -> definitionManagementService.createLocalDefinition(command(
                    invalidIdentifier,
                    WorkType.TASK,
                    "Invalid",
                    null,
                    "localhive.invalid",
                    1,
                    config("enabled", true),
                    adminUserId
            ))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldRequireObjectExecutorConfiguration() {
        UUID adminUserId = createUser("json-admin").getId();

        assertThatThrownBy(() -> definitionManagementService.createLocalDefinition(command(
                "localhive.array-config",
                WorkType.TASK,
                "Array Config",
                null,
                "localhive.array",
                1,
                JsonNodeFactory.instance.arrayNode(),
                adminUserId
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepExecutorConfigurationImmutableThroughGetter() {
        UUID adminUserId = createUser("immutability-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(command(
                "localhive.immutable-config",
                WorkType.TASK,
                "Immutable Config",
                null,
                "localhive.immutable",
                1,
                config("value", 1),
                adminUserId
        ));

        ObjectNode returnedConfiguration = (ObjectNode) version.getExecutorConfiguration();
        returnedConfiguration.put("value", 999);

        assertThat(version.getExecutorConfiguration().get("value").intValue()).isEqualTo(1);
    }

    @Test
    void shouldUseCanonicalChecksumForNestedObjectKeyOrderButKeepArrayOrderSignificant() {
        JsonNode firstConfiguration = nestedConfigInOrder("b", "a", List.of(1, 2));
        JsonNode secondConfiguration = nestedConfigInOrder("a", "b", List.of(1, 2));
        JsonNode reorderedArrayConfiguration = nestedConfigInOrder("a", "b", List.of(2, 1));

        String firstChecksum = checksumService.calculateChecksum(
                "localhive.checksum",
                WorkType.TASK,
                "Checksum",
                "same content",
                "localhive.checksum",
                1,
                firstConfiguration
        );
        String secondChecksum = checksumService.calculateChecksum(
                "localhive.checksum",
                WorkType.TASK,
                "Checksum",
                "same content",
                "localhive.checksum",
                1,
                secondConfiguration
        );
        String reorderedArrayChecksum = checksumService.calculateChecksum(
                "localhive.checksum",
                WorkType.TASK,
                "Checksum",
                "same content",
                "localhive.checksum",
                1,
                reorderedArrayConfiguration
        );

        assertThat(firstChecksum).matches("^[0-9a-f]{64}$");
        assertThat(secondChecksum).isEqualTo(firstChecksum);
        assertThat(reorderedArrayChecksum).isNotEqualTo(firstChecksum);
    }

    @Test
    void shouldRejectCrossSourceVersionCreationAndWorkTypeChanges() {
        UUID adminUserId = createUser("source-admin").getId();
        definitionManagementService.createLocalDefinition(command(
                "localhive.source-locked",
                WorkType.TASK,
                "Source Locked",
                null,
                "localhive.source",
                1,
                config("enabled", true),
                adminUserId
        ));

        assertThatThrownBy(() -> definitionManagementService.addImportedVersion(command(
                "localhive.source-locked",
                WorkType.TASK,
                "Imported",
                null,
                "localhive.source",
                1,
                config("enabled", true),
                adminUserId
        ))).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> definitionManagementService.addLocalVersion(command(
                "localhive.source-locked",
                WorkType.WORKLOAD,
                "Wrong Type",
                null,
                "localhive.source",
                1,
                config("enabled", true),
                adminUserId
        ))).isInstanceOf(IllegalStateException.class);
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static DefinitionContentCommand command(String logicalIdentifier,
                                                    WorkType workType,
                                                    String name,
                                                    String description,
                                                    String executorId,
                                                    int executorContractVersion,
                                                    JsonNode executorConfiguration,
                                                    UUID actorUserId) {
        return new DefinitionContentCommand(
                logicalIdentifier,
                workType,
                name,
                description,
                executorId,
                executorContractVersion,
                executorConfiguration,
                actorUserId
        );
    }

    private static ObjectNode config(String key, int value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(key, value);
        return node;
    }

    private static ObjectNode config(String key, String value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(key, value);
        return node;
    }

    private static ObjectNode config(String key, boolean value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(key, value);
        return node;
    }

    private static ObjectNode nestedConfigInOrder(String firstNestedKey,
                                                  String secondNestedKey,
                                                  List<Integer> arrayValues) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put(firstNestedKey, firstNestedKey.length());
        nested.put(secondNestedKey, secondNestedKey.length());

        ArrayNode orderedArray = JsonNodeFactory.instance.arrayNode();
        arrayValues.forEach(orderedArray::add);

        root.set("nested", nested);
        root.set("ordered", orderedArray);
        return root;
    }
}
