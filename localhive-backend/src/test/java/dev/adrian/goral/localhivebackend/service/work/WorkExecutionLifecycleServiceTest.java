package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
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
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkExecutionLifecycleService lifecycleService;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldLoadAndPersistSuccessfulTransitionChainWithAttempt() {
        WorkExecution execution = createQueuedExecution("lifecycle-success");
        LocalDateTime assignedAt = LocalDateTime.parse("2026-07-17T10:01:00");
        LocalDateTime claimedAt = LocalDateTime.parse("2026-07-17T10:02:00");
        LocalDateTime startedAt = LocalDateTime.parse("2026-07-17T10:03:00");
        LocalDateTime completedAt = LocalDateTime.parse("2026-07-17T10:04:00");

        ExecutionAssignment assignment = assign(execution, "lifecycle-success", assignedAt);
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
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getAssignment().getId()).isEqualTo(assignment.getId());
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
                    assertThat(attempt.getStartedAt()).isEqualTo(startedAt);
                    assertThat(attempt.getCompletedAt()).isEqualTo(completedAt);
                    assertThat(attempt.getFailureCode()).isNull();
                });
    }

    @Test
    void shouldPersistFailureFieldsOnExecutionAndAttempt() {
        WorkExecution execution = createQueuedExecution("lifecycle-failed");
        assign(execution, "lifecycle-failed", LocalDateTime.parse("2026-07-17T10:01:00"));
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
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
                    assertThat(attempt.getCompletedAt()).isEqualTo(LocalDateTime.parse("2026-07-17T10:04:00"));
                    assertThat(attempt.getFailureCode()).isEqualTo("EXECUTOR_ERROR");
                    assertThat(attempt.getFailureMessage()).isEqualTo("Process exited with status 1");
                });
    }

    @Test
    void shouldCreateAttemptOnlyWhenClaimedExecutionStartsRunning() {
        WorkExecution execution = createQueuedExecution("lifecycle-attempt");
        assign(execution, "lifecycle-attempt", LocalDateTime.parse("2026-07-17T10:01:00"));

        lifecycleService.markClaimed(execution.getId(), LocalDateTime.parse("2026-07-17T10:02:00"));
        assertThat(attemptRepository.existsByExecution(execution)).isFalse();

        lifecycleService.markRunning(execution.getId(), LocalDateTime.parse("2026-07-17T10:03:00"));

        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getStartedAt()).isEqualTo(LocalDateTime.parse("2026-07-17T10:03:00"));
                });
        assertThatThrownBy(() -> lifecycleService.markRunning(
                execution.getId(),
                LocalDateTime.parse("2026-07-17T10:04:00")
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCancelRunningAttemptAndKeepPreRunningStatesWithoutAttempt() {
        WorkExecution assignedExecution = createQueuedExecution("lifecycle-cancel-assigned");
        assign(assignedExecution, "lifecycle-cancel-assigned", LocalDateTime.parse("2026-07-17T10:01:00"));

        lifecycleService.cancel(assignedExecution.getId(), LocalDateTime.parse("2026-07-17T10:02:00"));

        assertThat(attemptRepository.existsByExecution(assignedExecution)).isFalse();
        assertThat(executionRepository.findById(assignedExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));

        WorkExecution runningExecution = createQueuedExecution("lifecycle-cancel-running");
        assign(runningExecution, "lifecycle-cancel-running", LocalDateTime.parse("2026-07-17T11:01:00"));
        lifecycleService.markClaimed(runningExecution.getId(), LocalDateTime.parse("2026-07-17T11:02:00"));
        lifecycleService.markRunning(runningExecution.getId(), LocalDateTime.parse("2026-07-17T11:03:00"));
        lifecycleService.cancel(runningExecution.getId(), LocalDateTime.parse("2026-07-17T11:04:00"));

        assertThat(attemptRepository.findByExecution(runningExecution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.CANCELLED);
                    assertThat(attempt.getCompletedAt()).isEqualTo(LocalDateTime.parse("2026-07-17T11:04:00"));
                });
        assertThat(executionRepository.findById(runningExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));
    }

    @Test
    void shouldExpireAssignedAndClaimedExecutionsWithoutAttempt() {
        WorkExecution assignedExecution = createQueuedExecution("lifecycle-expire-assigned");
        assign(assignedExecution, "lifecycle-expire-assigned", LocalDateTime.parse("2026-07-17T10:01:00"));

        lifecycleService.expire(assignedExecution.getId(), LocalDateTime.parse("2026-07-17T10:02:00"));

        assertThat(attemptRepository.existsByExecution(assignedExecution)).isFalse();
        assertThat(executionRepository.findById(assignedExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.EXPIRED));

        WorkExecution claimedExecution = createQueuedExecution("lifecycle-expire-claimed");
        assign(claimedExecution, "lifecycle-expire-claimed", LocalDateTime.parse("2026-07-17T11:01:00"));
        lifecycleService.markClaimed(claimedExecution.getId(), LocalDateTime.parse("2026-07-17T11:02:00"));

        lifecycleService.expire(claimedExecution.getId(), LocalDateTime.parse("2026-07-17T11:03:00"));

        assertThat(attemptRepository.existsByExecution(claimedExecution)).isFalse();
        assertThat(executionRepository.findById(claimedExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.EXPIRED));
    }

    @Test
    void shouldRejectUnknownExecutionAndRequireAssignmentBeforeClaimOrRun() {
        WorkExecution execution = createQueuedExecution("lifecycle-invalid");

        assertThatThrownBy(() -> lifecycleService.markClaimed(
                UUID.randomUUID(),
                LocalDateTime.parse("2026-07-17T10:02:00")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycleService.markClaimed(
                execution.getId(),
                LocalDateTime.parse("2026-07-17T10:02:00")
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycleService.markRunning(
                execution.getId(),
                LocalDateTime.parse("2026-07-17T10:03:00")
        )).isInstanceOf(IllegalStateException.class);

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
                    assertThat(stored.getClaimedAt()).isNull();
                    assertThat(stored.getStartedAt()).isNull();
                });
    }

    private ExecutionAssignment assign(WorkExecution execution, String suffix, LocalDateTime assignedAt) {
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .build());
        return assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                assignedAt
        );
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
