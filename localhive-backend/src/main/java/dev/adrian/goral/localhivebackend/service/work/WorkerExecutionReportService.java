package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkerExecutionReportService {

    private final ExecutionLeaseValidationService leaseValidationService;
    private final WorkExecutionLifecycleService lifecycleService;

    @Transactional
    public WorkExecution reportRunning(UUID workerId, UUID executionId, String rawLeaseToken, LocalDateTime now) {
        LocalDateTime startedAt = Objects.requireNonNull(now, "now must not be null.");
        leaseValidationService.validateLease(
                workerId,
                executionId,
                rawLeaseToken,
                startedAt,
                EnumSet.of(WorkExecutionStatus.CLAIMED)
        );
        return lifecycleService.markRunning(executionId, startedAt);
    }

    @Transactional
    public WorkExecution reportSucceeded(UUID workerId, UUID executionId, String rawLeaseToken, LocalDateTime completedAt) {
        LocalDateTime validCompletedAt = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        leaseValidationService.validateLease(
                workerId,
                executionId,
                rawLeaseToken,
                validCompletedAt,
                EnumSet.of(WorkExecutionStatus.RUNNING)
        );
        return lifecycleService.markSucceeded(executionId, validCompletedAt);
    }

    @Transactional
    public WorkExecution reportFailed(UUID workerId,
                                      UUID executionId,
                                      String rawLeaseToken,
                                      String failureCode,
                                      String failureMessage,
                                      LocalDateTime completedAt) {
        LocalDateTime validCompletedAt = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank.");
        }
        if (failureMessage != null && failureMessage.isBlank()) {
            throw new IllegalArgumentException("failureMessage must not be blank when present.");
        }

        leaseValidationService.validateLease(
                workerId,
                executionId,
                rawLeaseToken,
                validCompletedAt,
                EnumSet.of(WorkExecutionStatus.RUNNING)
        );
        return lifecycleService.markFailed(executionId, failureCode, failureMessage, validCompletedAt);
    }

    @Transactional
    public ExecutionAssignment renewLease(UUID workerId, UUID executionId, String rawLeaseToken, LocalDateTime now) {
        LocalDateTime renewedAt = Objects.requireNonNull(now, "now must not be null.");
        ExecutionAssignment assignment = leaseValidationService.validateLease(
                workerId,
                executionId,
                rawLeaseToken,
                renewedAt,
                EnumSet.of(WorkExecutionStatus.CLAIMED, WorkExecutionStatus.RUNNING)
        );
        assignment.renewLease(renewedAt.plusSeconds(WorkExecutionClaimService.LEASE_DURATION_SECONDS));
        return assignment;
    }
}
