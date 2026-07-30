package dev.adrian.goral.localhivebackend.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ExecutionGroupPersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

    @Autowired
    private ExecutionGroupRepository groupRepository;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private UserRepository userRepository;

    private User adminUser;

    @BeforeEach
    void resetDatabase() {
        executionRepository.deleteAll();
        groupRepository.deleteAll();
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser("execution-group-persistence-admin");
    }

    @Test
    void shouldPersistAndReadExecutionGroup() {
        LocalDateTime createdAt = LocalDateTime.parse("2026-07-21T10:00:00");

        ExecutionGroup group = groupRepository.save(ExecutionGroup.create(
                "  Optimization group  ",
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                4,
                createdAt
        ));

        assertThat(groupRepository.findById(group.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getDisplayName()).isEqualTo("Optimization group");
                    assertThat(stored.getStatus()).isEqualTo(ExecutionGroupStatus.CREATED);
                    assertThat(stored.getMergeMode()).isEqualTo(ExecutionGroupMergeMode.NONE);
                    assertThat(stored.getFailurePolicy()).isEqualTo(ExecutionGroupFailurePolicy.FAIL_FAST);
                    assertThat(stored.getShardCount()).isEqualTo(4);
                    assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
                    assertThat(stored.getUpdatedAt()).isEqualTo(createdAt);
                });
    }

    @Test
    void shouldPersistShardMetadataAndKeepStandaloneExecutionMetadataNull() {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = groupRepository.save(ExecutionGroup.create(
                "Repository group",
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                2,
                LocalDateTime.now()
        ));

        WorkExecution shard = createOneOff(version, "Shard 0");
        shard.attachToGroupAsShard(group, 0, 2);
        executionRepository.saveAndFlush(shard);

        WorkExecution standalone = createOneOff(version, "Standalone");

        assertThat(executionRepository.findById(shard.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getExecutionGroupId()).isEqualTo(group.getId());
                    assertThat(stored.getGroupRole()).isEqualTo(WorkExecutionGroupRole.SHARD);
                    assertThat(stored.getShardIndex()).isZero();
                    assertThat(stored.getShardCount()).isEqualTo(2);
                });
        assertThat(executionRepository.findById(standalone.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getExecutionGroupId()).isNull();
                    assertThat(stored.getGroupRole()).isNull();
                    assertThat(stored.getShardIndex()).isNull();
                    assertThat(stored.getShardCount()).isNull();
                });
    }

    private WorkExecution createOneOff(WorkDefinitionVersion version, String displayName) {
        return creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                JsonNodeFactory.instance.objectNode(),
                null,
                displayName
        ));
    }

    private WorkDefinitionVersion noOpVersion() {
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive.execution-group-persistence-" + UUID.randomUUID(),
                WorkType.TASK,
                "NO_OP",
                null,
                "localhive.no-op",
                1,
                JsonNodeFactory.instance.objectNode().put("message", "noop"),
                ResourceRequest.zero(),
                adminUser.getId()
        ));
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }
}
