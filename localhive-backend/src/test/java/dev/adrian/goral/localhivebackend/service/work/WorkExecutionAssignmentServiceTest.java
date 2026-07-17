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
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkExecutionAssignmentServiceTest {

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
    private WorkExecutionRepository executionRepository;

    @Autowired
    private ExecutionAssignmentRepository assignmentRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldAssignQueuedExecutionToEligibleWorkerAndPersistAssignment() {
        WorkExecution execution = createQueuedExecution("assignment-success");
        Worker worker = createWorker(
                "assignment-success",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );

        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );

        assertThat(assignment.getId()).isNotNull();
        assertThat(assignment.getExecution().getId()).isEqualTo(execution.getId());
        assertThat(assignment.getWorker().getId()).isEqualTo(worker.getId());
        assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.AUTO);
        assertThat(assignment.getAssignedAt()).isEqualTo(BASE_TIME.plusMinutes(1));
        assertThat(assignmentRepository.findByExecution(assignment.getExecution()))
                .hasValueSatisfying(stored -> assertThat(stored.getId()).isEqualTo(assignment.getId()));
        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED);
                    assertThat(stored.getAssignedAt()).isEqualTo(BASE_TIME.plusMinutes(1));
                });
    }

    @Test
    void shouldRejectPendingOfflineAndPausedWorkersWithoutMutatingExecution() {
        List<WorkerState> invalidStates = List.of(
                new WorkerState(
                        WorkerApprovalStatus.PENDING,
                        WorkerConnectionStatus.ONLINE,
                        WorkerAvailabilityStatus.AVAILABLE
                ),
                new WorkerState(
                        WorkerApprovalStatus.APPROVED,
                        WorkerConnectionStatus.OFFLINE,
                        WorkerAvailabilityStatus.AVAILABLE
                ),
                new WorkerState(
                        WorkerApprovalStatus.APPROVED,
                        WorkerConnectionStatus.ONLINE,
                        WorkerAvailabilityStatus.PAUSED
                )
        );

        for (WorkerState state : invalidStates) {
            WorkExecution execution = createQueuedExecution("assignment-ineligible");
            Worker worker = createWorker(
                    "assignment-ineligible",
                    state.approvalStatus(),
                    state.connectionStatus(),
                    state.availabilityStatus()
            );

            assertThatThrownBy(() -> assignmentService.assignExecution(
                    execution.getId(),
                    worker.getId(),
                    ExecutionAssignmentMode.AUTO,
                    BASE_TIME.plusMinutes(1)
            )).isInstanceOf(IllegalStateException.class);

            assertThat(assignmentRepository.existsByExecution(execution)).isFalse();
            assertThat(executionRepository.findById(execution.getId()))
                    .hasValueSatisfying(stored -> {
                        assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.QUEUED);
                        assertThat(stored.getAssignedAt()).isNull();
                    });
        }
    }

    @Test
    void shouldRejectMissingInputsMissingEntitiesAndNonQueuedExecutions() {
        WorkExecution execution = createQueuedExecution("assignment-invalid");
        Worker worker = createWorker(
                "assignment-invalid",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );

        assertThatThrownBy(() -> assignmentService.assignExecution(
                null,
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignmentService.assignExecution(
                execution.getId(),
                null,
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                null,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                null
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> assignmentService.assignExecution(
                UUID.randomUUID(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignmentService.assignExecution(
                execution.getId(),
                UUID.randomUUID(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);

        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );
        Worker otherWorker = createWorker(
                "assignment-invalid-other",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );

        assertThatThrownBy(() -> assignmentService.assignExecution(
                execution.getId(),
                otherWorker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(2)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldBlockWorkerWithAssignedClaimedOrRunningExecution() {
        assertActiveStatusBlocksWorker(WorkExecutionStatus.ASSIGNED);
        assertActiveStatusBlocksWorker(WorkExecutionStatus.CLAIMED);
        assertActiveStatusBlocksWorker(WorkExecutionStatus.RUNNING);
    }

    @Test
    void shouldAllowWorkerWhenOtherExecutionsAreQueuedOrTerminal() {
        Worker queuedWorker = createWorker(
                "assignment-queued-nonblocking",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        createQueuedExecution("assignment-queued-nonblocking");
        assertCanAssignFreshExecution(queuedWorker, "assignment-queued-nonblocking");

        for (WorkExecutionStatus terminalStatus : List.of(
                WorkExecutionStatus.SUCCEEDED,
                WorkExecutionStatus.FAILED,
                WorkExecutionStatus.CANCELLED,
                WorkExecutionStatus.EXPIRED
        )) {
            Worker worker = createWorker(
                    "assignment-terminal-" + terminalStatus.name().toLowerCase(),
                    WorkerApprovalStatus.APPROVED,
                    WorkerConnectionStatus.ONLINE,
                    WorkerAvailabilityStatus.AVAILABLE
            );
            transitionAssignedExecutionTo(worker, terminalStatus);

            assertCanAssignFreshExecution(worker, "assignment-terminal-" + terminalStatus.name().toLowerCase());
        }
    }

    private void assertActiveStatusBlocksWorker(WorkExecutionStatus status) {
        Worker worker = createWorker(
                "assignment-active-" + status.name().toLowerCase(),
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        WorkExecution activeExecution = createQueuedExecution("assignment-active-" + status.name().toLowerCase());
        assignmentService.assignExecution(
                activeExecution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );
        if (status == WorkExecutionStatus.CLAIMED || status == WorkExecutionStatus.RUNNING) {
            lifecycleService.markClaimed(activeExecution.getId(), BASE_TIME.plusMinutes(2));
        }
        if (status == WorkExecutionStatus.RUNNING) {
            lifecycleService.markRunning(activeExecution.getId(), BASE_TIME.plusMinutes(3));
        }

        WorkExecution secondExecution = createQueuedExecution("assignment-active-second-" + status.name().toLowerCase());

        assertThatThrownBy(() -> assignmentService.assignExecution(
                secondExecution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(4)
        )).isInstanceOf(IllegalStateException.class);
        assertThat(assignmentRepository.existsByExecution(secondExecution)).isFalse();
    }

    private void transitionAssignedExecutionTo(Worker worker, WorkExecutionStatus status) {
        WorkExecution execution = createQueuedExecution("assignment-terminal-source-" + status.name().toLowerCase());
        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );

        switch (status) {
            case SUCCEEDED -> {
                lifecycleService.markClaimed(execution.getId(), BASE_TIME.plusMinutes(2));
                lifecycleService.markRunning(execution.getId(), BASE_TIME.plusMinutes(3));
                lifecycleService.markSucceeded(execution.getId(), BASE_TIME.plusMinutes(4));
            }
            case FAILED -> {
                lifecycleService.markClaimed(execution.getId(), BASE_TIME.plusMinutes(2));
                lifecycleService.markRunning(execution.getId(), BASE_TIME.plusMinutes(3));
                lifecycleService.markFailed(
                        execution.getId(),
                        "EXECUTOR_ERROR",
                        "Process exited with status 1",
                        BASE_TIME.plusMinutes(4)
                );
            }
            case CANCELLED -> lifecycleService.cancel(execution.getId(), BASE_TIME.plusMinutes(2));
            case EXPIRED -> lifecycleService.expire(execution.getId(), BASE_TIME.plusMinutes(2));
            default -> throw new IllegalArgumentException("Unsupported terminal status: " + status);
        }
    }

    private void assertCanAssignFreshExecution(Worker worker, String suffix) {
        WorkExecution execution = createQueuedExecution(suffix + "-fresh");

        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.PREFER,
                BASE_TIME.plusMinutes(10)
        );

        assertThat(assignment.getExecution().getId()).isEqualTo(execution.getId());
        assertThat(assignment.getWorker().getId()).isEqualTo(worker.getId());
        assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.PREFER);
    }

    private WorkExecution createQueuedExecution(String suffix) {
        UUID adminUserId = createUser(suffix + "-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive." + suffix + "-" + UUID.randomUUID(),
                WorkType.TASK,
                "Assignment Definition",
                null,
                "localhive.assignment-executor",
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

    private Worker createWorker(String suffix,
                                WorkerApprovalStatus approvalStatus,
                                WorkerConnectionStatus connectionStatus,
                                WorkerAvailabilityStatus availabilityStatus) {
        return workerRepository.save(Worker.builder()
                .hostname("worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(approvalStatus)
                .connectionStatus(connectionStatus)
                .availabilityStatus(availabilityStatus)
                .build());
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private record WorkerState(
            WorkerApprovalStatus approvalStatus,
            WorkerConnectionStatus connectionStatus,
            WorkerAvailabilityStatus availabilityStatus
    ) {
    }
}
