package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAttempt;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkExecutionLifecycleService {

    private final WorkExecutionRepository executionRepository;
    private final ExecutionAssignmentRepository assignmentRepository;
    private final ExecutionAttemptRepository attemptRepository;

    @Transactional
    public WorkExecution markClaimed(UUID executionId, LocalDateTime claimedAt) {
        WorkExecution execution = findExecution(executionId);
        requireAssignment(execution);
        execution.markClaimed(claimedAt);
        return execution;
    }

    @Transactional
    public WorkExecution markRunning(UUID executionId, LocalDateTime startedAt) {
        WorkExecution execution = findExecution(executionId);
        ExecutionAssignment assignment = requireAssignment(execution);
        requireNoAttempt(execution);
        execution.markRunning(startedAt);
        ExecutionAttempt attempt = ExecutionAttempt.createRunning(execution, assignment, 1, startedAt);
        attemptRepository.save(attempt);
        return execution;
    }

    @Transactional
    public WorkExecution markSucceeded(UUID executionId, LocalDateTime completedAt) {
        WorkExecution execution = findExecution(executionId);
        requireRunningExecution(execution, "mark execution as succeeded");
        ExecutionAttempt attempt = requireRunningAttempt(execution);
        execution.markSucceeded(completedAt);
        attempt.markSucceeded(completedAt);
        return execution;
    }

    @Transactional
    public WorkExecution markFailed(UUID executionId,
                                    String failureCode,
                                    String failureMessage,
                                    LocalDateTime completedAt) {
        WorkExecution execution = findExecution(executionId);
        requireRunningExecution(execution, "mark execution as failed");
        ExecutionAttempt attempt = requireRunningAttempt(execution);
        execution.markFailed(failureCode, failureMessage, completedAt);
        attempt.markFailed(failureCode, failureMessage, completedAt);
        return execution;
    }

    @Transactional
    public WorkExecution cancel(UUID executionId, LocalDateTime cancelledAt) {
        WorkExecution execution = findExecution(executionId);
        if (execution.getStatus() == WorkExecutionStatus.RUNNING) {
            ExecutionAttempt attempt = requireRunningAttempt(execution);
            execution.cancel(cancelledAt);
            attempt.markCancelled(cancelledAt);
            return execution;
        }

        if (execution.getStatus() == WorkExecutionStatus.QUEUED
                || execution.getStatus() == WorkExecutionStatus.ASSIGNED
                || execution.getStatus() == WorkExecutionStatus.CLAIMED) {
            requireNoAttempt(execution);
        }

        execution.cancel(cancelledAt);
        return execution;
    }

    @Transactional
    public WorkExecution expire(UUID executionId, LocalDateTime expiredAt) {
        WorkExecution execution = findExecution(executionId);
        if (execution.getStatus() == WorkExecutionStatus.ASSIGNED
                || execution.getStatus() == WorkExecutionStatus.CLAIMED) {
            requireNoAttempt(execution);
        }

        execution.expire(expiredAt);
        return execution;
    }

    private WorkExecution findExecution(UUID executionId) {
        if (executionId == null) {
            throw new IllegalArgumentException("executionId must not be null.");
        }

        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Work execution not found: " + executionId));
    }

    private ExecutionAssignment requireAssignment(WorkExecution execution) {
        return assignmentRepository.findByExecution(execution)
                .orElseThrow(() -> new IllegalStateException(
                        "Execution assignment is required for execution: " + execution.getId()
                ));
    }

    private void requireNoAttempt(WorkExecution execution) {
        if (attemptRepository.existsByExecution(execution)) {
            throw new IllegalStateException("Execution already has an attempt.");
        }
    }

    private ExecutionAttempt requireRunningAttempt(WorkExecution execution) {
        return attemptRepository.findByExecutionAndStatus(execution, ExecutionAttemptStatus.RUNNING)
                .orElseThrow(() -> new IllegalStateException(
                        "Running execution attempt is required for execution: " + execution.getId()
                ));
    }

    private static void requireRunningExecution(WorkExecution execution, String action) {
        Objects.requireNonNull(execution, "execution must not be null.");
        if (execution.getStatus() != WorkExecutionStatus.RUNNING) {
            throw new IllegalStateException("Cannot " + action + " from status " + execution.getStatus() + ".");
        }
    }
}
