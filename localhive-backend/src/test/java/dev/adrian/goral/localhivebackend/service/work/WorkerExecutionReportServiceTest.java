package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAttempt;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class WorkerExecutionReportServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkExecutionClaimService claimService;

    @Autowired
    private WorkerExecutionReportService reportService;

    @Autowired
    private WorkExecutionCreationService creationService;

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
    void shouldReportRunningWithValidLeaseAndCreateAttempt() {
        ClaimedExecution claimed = createClaimedExecution("report-running", BASE_TIME);

        WorkExecution running = reportService.reportRunning(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(1)
        );

        assertThat(running.getStatus()).isEqualTo(WorkExecutionStatus.RUNNING);
        assertThat(attemptRepository.findByExecution(claimed.execution()))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
                    assertThat(attempt.getAssignment().getId()).isEqualTo(claimed.assignment().getId());
                });
    }

    @Test
    void shouldRejectRunningWithInvalidExpiredOrWrongWorkerLease() {
        ClaimedExecution claimed = createClaimedExecution("report-running-invalid", BASE_TIME);
        Worker wrongWorker = createWorker("report-running-wrong-worker");

        assertThatThrownBy(() -> reportService.reportRunning(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                "bad-token",
                BASE_TIME.plusSeconds(1)
        )).isInstanceOf(ExecutionLeaseException.class);
        assertThatThrownBy(() -> reportService.reportRunning(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(60)
        )).isInstanceOf(ExecutionLeaseException.class);
        assertThatThrownBy(() -> reportService.reportRunning(
                wrongWorker.getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(1)
        )).isInstanceOf(ExecutionLeaseException.class);
    }

    @Test
    void shouldReportSucceededWithValidRunningLease() {
        ClaimedExecution claimed = createRunningExecution("report-succeeded");

        WorkExecution succeeded = reportService.reportSucceeded(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(2)
        );

        assertThat(succeeded.getStatus()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
        assertThat(attemptRepository.findByExecution(claimed.execution()))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
                    assertThat(attempt.getCompletedAt()).isEqualTo(BASE_TIME.plusSeconds(2));
                });
    }

    @Test
    void shouldRejectSucceededWhenExecutionIsNotRunning() {
        ClaimedExecution claimed = createClaimedExecution("report-succeeded-invalid", BASE_TIME);

        assertThatThrownBy(() -> reportService.reportSucceeded(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(1)
        )).isInstanceOf(ExecutionLeaseException.class);
    }

    @Test
    void shouldReportFailedWithValidRunningLeaseAndFailureFields() {
        ClaimedExecution claimed = createRunningExecution("report-failed");

        WorkExecution failed = reportService.reportFailed(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                "EXECUTOR_FAILED",
                "Process exited with status 1",
                BASE_TIME.plusSeconds(2)
        );

        assertThat(failed.getStatus()).isEqualTo(WorkExecutionStatus.FAILED);
        assertThat(executionRepository.findById(claimed.execution().getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getFailureCode()).isEqualTo("EXECUTOR_FAILED");
                    assertThat(stored.getFailureMessage()).isEqualTo("Process exited with status 1");
                });
        assertThat(attemptRepository.findByExecution(claimed.execution()))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
                    assertThat(attempt.getFailureCode()).isEqualTo("EXECUTOR_FAILED");
                    assertThat(attempt.getFailureMessage()).isEqualTo("Process exited with status 1");
                });
    }

    @Test
    void shouldRejectFailedReportWithBlankFailureCode() {
        ClaimedExecution claimed = createRunningExecution("report-failed-blank");

        assertThatThrownBy(() -> reportService.reportFailed(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                " ",
                null,
                BASE_TIME.plusSeconds(2)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRenewClaimedOrRunningLeaseWithoutRotatingToken() {
        ClaimedExecution claimed = createClaimedExecution("report-renew", BASE_TIME);
        String originalHash = assignmentRepository.findByExecution(claimed.execution()).orElseThrow().getLeaseTokenHash();

        ExecutionAssignment renewed = reportService.renewLease(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(30)
        );

        assertThat(renewed.getLeaseExpiresAt())
                .isEqualTo(BASE_TIME.plusSeconds(30 + WorkExecutionClaimService.LEASE_DURATION_SECONDS));
        assertThat(renewed.getLeaseTokenHash()).isEqualTo(originalHash);

        reportService.reportRunning(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(31)
        );

        ExecutionAssignment renewedRunning = reportService.renewLease(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(60)
        );

        assertThat(renewedRunning.getLeaseTokenHash()).isEqualTo(originalHash);
    }

    @Test
    void shouldRejectRenewAfterTerminalStatusOrExpiredLease() {
        ClaimedExecution succeeded = createRunningExecution("report-renew-succeeded");
        reportService.reportSucceeded(
                succeeded.assignment().getWorker().getId(),
                succeeded.execution().getId(),
                succeeded.rawLeaseToken(),
                BASE_TIME.plusSeconds(2)
        );

        assertThatThrownBy(() -> reportService.renewLease(
                succeeded.assignment().getWorker().getId(),
                succeeded.execution().getId(),
                succeeded.rawLeaseToken(),
                BASE_TIME.plusSeconds(3)
        )).isInstanceOf(ExecutionLeaseException.class);

        ClaimedExecution expired = createClaimedExecution("report-renew-expired", BASE_TIME);
        assertThatThrownBy(() -> reportService.renewLease(
                expired.assignment().getWorker().getId(),
                expired.execution().getId(),
                expired.rawLeaseToken(),
                BASE_TIME.plusSeconds(60)
        )).isInstanceOf(ExecutionLeaseException.class);
    }

    private ClaimedExecution createRunningExecution(String suffix) {
        ClaimedExecution claimed = createClaimedExecution(suffix, BASE_TIME);
        reportService.reportRunning(
                claimed.assignment().getWorker().getId(),
                claimed.execution().getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(1)
        );
        return claimed;
    }

    private ClaimedExecution createClaimedExecution(String suffix, LocalDateTime claimedAt) {
        Worker worker = createWorker(suffix);
        WorkExecution execution = createQueuedExecution(suffix);
        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.minusMinutes(1)
        );
        return claimService.claimNextAssignedExecution(worker.getId(), claimedAt).orElseThrow();
    }

    private WorkExecution createQueuedExecution(String suffix) {
        UUID adminUserId = createUser(suffix + "-admin").getId();
        WorkDefinitionVersion version = definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive." + suffix + "-" + UUID.randomUUID(),
                WorkType.TASK,
                "Report Definition",
                null,
                "localhive.report-executor",
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
}
