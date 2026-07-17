package dev.adrian.goral.localhivebackend.repository;

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
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionLifecycleService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class ExecutionAssignmentAttemptPersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

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
    private ExecutionAssignmentRepository assignmentRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAssignmentAndAttemptWithRepositoryAccess() {
        Worker worker = createWorker("assignment-attempt-persistence");
        WorkExecution execution = createQueuedExecution("assignment-attempt-persistence");

        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusMinutes(1)
        );
        lifecycleService.markClaimed(execution.getId(), BASE_TIME.plusMinutes(2));
        lifecycleService.markRunning(execution.getId(), BASE_TIME.plusMinutes(3));

        assertThat(assignmentRepository.findByExecution(execution))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getId()).isEqualTo(assignment.getId());
                    assertThat(stored.getExecution().getId()).isEqualTo(execution.getId());
                    assertThat(stored.getWorker().getId()).isEqualTo(worker.getId());
                    assertThat(stored.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.REQUIRE);
                    assertThat(stored.getAssignedAt()).isEqualTo(BASE_TIME.plusMinutes(1));
                });
        assertThat(assignmentRepository.existsByWorkerAndExecution_StatusIn(
                worker,
                List.of(WorkExecutionStatus.ASSIGNED, WorkExecutionStatus.CLAIMED, WorkExecutionStatus.RUNNING)
        )).isTrue();
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getExecution().getId()).isEqualTo(execution.getId());
                    assertThat(attempt.getAssignment().getId()).isEqualTo(assignment.getId());
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
                    assertThat(attempt.getStartedAt()).isEqualTo(BASE_TIME.plusMinutes(3));
                    assertThat(attempt.getCompletedAt()).isNull();
                });
    }

    @Test
    void shouldEnforceDatabaseConstraintsForAssignmentsAndAttempts() {
        Worker worker = createWorker("assignment-attempt-constraints");
        WorkExecution execution = createQueuedExecution("assignment-attempt-constraints");
        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );

        assertThatThrownBy(() -> insertAssignment(
                execution.getId(),
                worker.getId(),
                "AUTO"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAssignment(
                createQueuedExecution("assignment-attempt-invalid-mode").getId(),
                worker.getId(),
                "DIRECT"
        )).isInstanceOf(DataIntegrityViolationException.class);

        lifecycleService.markClaimed(execution.getId(), BASE_TIME.plusMinutes(2));
        lifecycleService.markRunning(execution.getId(), BASE_TIME.plusMinutes(3));

        assertThatThrownBy(() -> insertAttempt(
                execution.getId(),
                assignment.getId(),
                1,
                "RUNNING",
                null,
                null,
                null
        )).isInstanceOf(DataIntegrityViolationException.class);

        AssignedExecution assignedForInvalidStatus = createAssignedExecution("assignment-attempt-invalid-status");
        assertThatThrownBy(() -> insertAttempt(
                assignedForInvalidStatus.executionId(),
                assignedForInvalidStatus.assignmentId(),
                1,
                "STARTED",
                null,
                null,
                null
        )).isInstanceOf(DataIntegrityViolationException.class);

        AssignedExecution assignedForAttemptNumber = createAssignedExecution("assignment-attempt-number");
        assertThatThrownBy(() -> insertAttempt(
                assignedForAttemptNumber.executionId(),
                assignedForAttemptNumber.assignmentId(),
                2,
                "RUNNING",
                null,
                null,
                null
        )).isInstanceOf(DataIntegrityViolationException.class);

        AssignedExecution assignedForBlankFailureMessage = createAssignedExecution("assignment-attempt-blank-message");
        assertThatThrownBy(() -> insertAttempt(
                assignedForBlankFailureMessage.executionId(),
                assignedForBlankFailureMessage.assignmentId(),
                1,
                "FAILED",
                BASE_TIME.plusMinutes(4),
                "EXECUTOR_ERROR",
                " "
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private AssignedExecution createAssignedExecution(String suffix) {
        Worker worker = createWorker(suffix);
        WorkExecution execution = createQueuedExecution(suffix);
        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );
        return new AssignedExecution(execution.getId(), assignment.getId());
    }

    private void insertAssignment(UUID executionId, UUID workerId, String assignmentMode) {
        jdbcTemplate.update("""
                insert into execution_assignments (
                    id,
                    execution_id,
                    worker_id,
                    assignment_mode,
                    assigned_at
                ) values (?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID(),
                executionId,
                workerId,
                assignmentMode
        );
    }

    private void insertAttempt(UUID executionId,
                               UUID assignmentId,
                               int attemptNumber,
                               String status,
                               LocalDateTime completedAt,
                               String failureCode,
                               String failureMessage) {
        jdbcTemplate.update("""
                insert into execution_attempts (
                    id,
                    execution_id,
                    assignment_id,
                    attempt_number,
                    status,
                    started_at,
                    completed_at,
                    failure_code,
                    failure_message
                ) values (?, ?, ?, ?, ?, current_timestamp, ?, ?, ?)
                """,
                UUID.randomUUID(),
                executionId,
                assignmentId,
                attemptNumber,
                status,
                completedAt,
                failureCode,
                failureMessage
        );
    }

    private WorkExecution createQueuedExecution(String suffix) {
        UUID adminUserId = createUser(suffix + "-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive." + suffix + "-" + UUID.randomUUID(),
                WorkType.TASK,
                "Persistence Definition",
                null,
                "localhive.persistence-executor",
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

    private Worker createWorker(String suffix) {
        return workerRepository.save(Worker.builder()
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
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private record AssignedExecution(UUID executionId, UUID assignmentId) {
    }
}
