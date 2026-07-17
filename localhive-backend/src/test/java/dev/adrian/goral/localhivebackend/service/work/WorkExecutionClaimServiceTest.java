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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkExecutionClaimServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionClaimService claimService;

    @Autowired
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkExecutionLifecycleService lifecycleService;

    @Autowired
    private ExecutionLeaseTokenService leaseTokenService;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private ExecutionAssignmentRepository assignmentRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldClaimAssignedExecutionForWorkerAndStoreOnlyLeaseHash() {
        Worker worker = createWorker("claim-success", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        WorkExecution execution = createQueuedExecution("claim-success");
        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.minusMinutes(1)
        );

        ClaimedExecution claimed = claimService.claimNextAssignedExecution(worker.getId(), BASE_TIME).orElseThrow();

        assertThat(claimed.execution().getId()).isEqualTo(execution.getId());
        assertThat(claimed.assignment().getId()).isEqualTo(assignment.getId());
        assertThat(claimed.rawLeaseToken()).isNotBlank();
        assertThat(attemptRepository.existsByExecution(execution)).isFalse();
        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CLAIMED);
                    assertThat(stored.getClaimedAt()).isEqualTo(BASE_TIME);
                });
        assertThat(assignmentRepository.findByExecution(execution))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getClaimedAt()).isEqualTo(BASE_TIME);
                    assertThat(stored.getLeaseExpiresAt())
                            .isEqualTo(BASE_TIME.plusSeconds(WorkExecutionClaimService.LEASE_DURATION_SECONDS));
                    assertThat(stored.getLeaseTokenHash()).isNotBlank();
                    assertThat(stored.getLeaseTokenHash()).isNotEqualTo(claimed.rawLeaseToken());
                    assertThat(leaseTokenService.matches(claimed.rawLeaseToken(), stored.getLeaseTokenHash())).isTrue();
                });
    }

    @Test
    void shouldClaimOldestAssignedExecutionFirst() {
        Worker worker = createWorker("claim-oldest", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        WorkExecution olderExecution = createDirectlyAssignedExecution(
                "claim-oldest-older",
                worker,
                BASE_TIME.minusMinutes(10)
        );
        WorkExecution newerExecution = createDirectlyAssignedExecution(
                "claim-oldest-newer",
                worker,
                BASE_TIME.minusMinutes(1)
        );

        ClaimedExecution claimed = claimService.claimNextAssignedExecution(worker.getId(), BASE_TIME).orElseThrow();

        assertThat(claimed.execution().getId()).isEqualTo(olderExecution.getId());
        assertThat(executionRepository.findById(olderExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CLAIMED));
        assertThat(executionRepository.findById(newerExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED));
    }

    @Test
    void shouldReturnEmptyWhenWorkerHasNoAssignedWorkOrOnlyOtherWorkersWork() {
        Worker worker = createWorker("claim-empty", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        Worker otherWorker = createWorker("claim-empty-other", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        WorkExecution otherExecution = createQueuedExecution("claim-empty-other");
        assignmentService.assignExecution(otherExecution.getId(), otherWorker.getId(), ExecutionAssignmentMode.AUTO, BASE_TIME);

        assertThat(claimService.claimNextAssignedExecution(worker.getId(), BASE_TIME)).isEmpty();
    }

    @Test
    void shouldRejectPendingOfflineAndPausedWorkers() {
        List<Worker> workers = List.of(
                createWorker("claim-pending", WorkerApprovalStatus.PENDING,
                        WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE),
                createWorker("claim-offline", WorkerApprovalStatus.APPROVED,
                        WorkerConnectionStatus.OFFLINE, WorkerAvailabilityStatus.AVAILABLE),
                createWorker("claim-paused", WorkerApprovalStatus.APPROVED,
                        WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.PAUSED)
        );

        for (Worker worker : workers) {
            assertThatThrownBy(() -> claimService.claimNextAssignedExecution(worker.getId(), BASE_TIME))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shouldRejectAlreadyClaimedAssignmentIfAssignedStatusIsInconsistent() {
        Worker worker = createWorker("claim-duplicate", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        WorkExecution execution = createDirectlyAssignedExecution("claim-duplicate", worker, BASE_TIME.minusMinutes(1));
        ExecutionAssignment assignment = assignmentRepository.findByExecution(execution).orElseThrow();
        assignment.claim("existing-hash", BASE_TIME.minusMinutes(1), BASE_TIME.plusMinutes(1));
        assignmentRepository.save(assignment);

        assertThatThrownBy(() -> claimService.claimNextAssignedExecution(worker.getId(), BASE_TIME))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldExpireOnlyStaleClaimedExecutions() {
        Worker claimedWorker = createWorker("claim-expire-claimed", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        WorkExecution claimedExecution = createQueuedExecution("claim-expire-claimed");
        assignmentService.assignExecution(
                claimedExecution.getId(),
                claimedWorker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.minusMinutes(5)
        );
        claimService.claimNextAssignedExecution(claimedWorker.getId(), BASE_TIME.minusSeconds(120));

        Worker runningWorker = createWorker("claim-expire-running", WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE, WorkerAvailabilityStatus.AVAILABLE);
        WorkExecution runningExecution = createQueuedExecution("claim-expire-running");
        assignmentService.assignExecution(
                runningExecution.getId(),
                runningWorker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.minusMinutes(5)
        );
        claimService.claimNextAssignedExecution(runningWorker.getId(), BASE_TIME.minusSeconds(120));
        lifecycleService.markRunning(runningExecution.getId(), BASE_TIME.minusSeconds(90));

        int expired = claimService.expireStaleClaims(BASE_TIME);

        assertThat(expired).isEqualTo(1);
        assertThat(executionRepository.findById(claimedExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.EXPIRED));
        assertThat(executionRepository.findById(runningExecution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.RUNNING));
    }

    private WorkExecution createDirectlyAssignedExecution(String suffix, Worker worker, LocalDateTime assignedAt) {
        WorkExecution execution = createQueuedExecution(suffix);
        execution.markAssigned(assignedAt);
        executionRepository.save(execution);
        assignmentRepository.save(ExecutionAssignment.create(
                execution,
                worker,
                ExecutionAssignmentMode.AUTO,
                assignedAt
        ));
        return execution;
    }

    private WorkExecution createQueuedExecution(String suffix) {
        UUID adminUserId = createUser(suffix + "-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive." + suffix + "-" + UUID.randomUUID(),
                WorkType.TASK,
                "Claim Definition",
                null,
                "localhive.claim-executor",
                1,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                adminUserId
        ));
        return executionRepository.save(WorkExecution.createQueued(
                version,
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                BASE_TIME
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
}
