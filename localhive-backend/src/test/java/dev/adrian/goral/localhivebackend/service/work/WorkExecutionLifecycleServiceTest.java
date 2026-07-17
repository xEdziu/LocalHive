package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkExecutionLifecycleServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

    @Autowired
    private WorkExecutionLifecycleService lifecycleService;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldLoadAndPersistSuccessfulTransitionChain() {
        WorkExecution execution = createQueuedExecution("lifecycle-success");
        LocalDateTime assignedAt = LocalDateTime.parse("2026-07-17T10:01:00");
        LocalDateTime claimedAt = LocalDateTime.parse("2026-07-17T10:02:00");
        LocalDateTime startedAt = LocalDateTime.parse("2026-07-17T10:03:00");
        LocalDateTime completedAt = LocalDateTime.parse("2026-07-17T10:04:00");

        lifecycleService.markAssigned(execution.getId(), assignedAt);
        lifecycleService.markClaimed(execution.getId(), claimedAt);
        lifecycleService.markRunning(execution.getId(), startedAt);
        lifecycleService.markSucceeded(execution.getId(), completedAt);

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
                    assertThat(stored.getAssignedAt()).isEqualTo(assignedAt);
                    assertThat(stored.getClaimedAt()).isEqualTo(claimedAt);
                    assertThat(stored.getStartedAt()).isEqualTo(startedAt);
                    assertThat(stored.getCompletedAt()).isEqualTo(completedAt);
                    assertThat(stored.getFailureCode()).isNull();
                });
    }

    @Test
    void shouldPersistFailureFields() {
        WorkExecution execution = createQueuedExecution("lifecycle-failed");
        lifecycleService.markAssigned(execution.getId(), LocalDateTime.parse("2026-07-17T10:01:00"));
        lifecycleService.markClaimed(execution.getId(), LocalDateTime.parse("2026-07-17T10:02:00"));
        lifecycleService.markRunning(execution.getId(), LocalDateTime.parse("2026-07-17T10:03:00"));

        lifecycleService.markFailed(
                execution.getId(),
                "EXECUTOR_ERROR",
                "Process exited with status 1",
                LocalDateTime.parse("2026-07-17T10:04:00")
        );

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.FAILED);
                    assertThat(stored.getCompletedAt()).isEqualTo(LocalDateTime.parse("2026-07-17T10:04:00"));
                    assertThat(stored.getFailureCode()).isEqualTo("EXECUTOR_ERROR");
                    assertThat(stored.getFailureMessage()).isEqualTo("Process exited with status 1");
                });
    }

    @Test
    void shouldRejectUnknownExecutionAndPropagateInvalidTransitions() {
        WorkExecution execution = createQueuedExecution("lifecycle-invalid");

        assertThatThrownBy(() -> lifecycleService.markAssigned(
                UUID.randomUUID(),
                LocalDateTime.parse("2026-07-17T10:01:00")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycleService.markRunning(
                execution.getId(),
                LocalDateTime.parse("2026-07-17T10:03:00")
        )).isInstanceOf(IllegalStateException.class);

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
                    assertThat(stored.getStartedAt()).isNull();
                });
    }

    private WorkExecution createQueuedExecution(String suffix) {
        UUID adminUserId = createUser(suffix + "-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive." + suffix + "-" + UUID.randomUUID(),
                WorkType.TASK,
                "Lifecycle Definition",
                null,
                "localhive.lifecycle-executor",
                1,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));
        return creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                JsonNodeFactory.instance.objectNode(),
                null
        ));
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }
}
