package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkExecutionLifecycleService {

    private final WorkExecutionRepository executionRepository;

    @Transactional
    public WorkExecution markAssigned(UUID executionId, LocalDateTime assignedAt) {
        WorkExecution execution = findExecution(executionId);
        execution.markAssigned(assignedAt);
        return execution;
    }

    @Transactional
    public WorkExecution markClaimed(UUID executionId, LocalDateTime claimedAt) {
        WorkExecution execution = findExecution(executionId);
        execution.markClaimed(claimedAt);
        return execution;
    }

    @Transactional
    public WorkExecution markRunning(UUID executionId, LocalDateTime startedAt) {
        WorkExecution execution = findExecution(executionId);
        execution.markRunning(startedAt);
        return execution;
    }

    @Transactional
    public WorkExecution markSucceeded(UUID executionId, LocalDateTime completedAt) {
        WorkExecution execution = findExecution(executionId);
        execution.markSucceeded(completedAt);
        return execution;
    }

    @Transactional
    public WorkExecution markFailed(UUID executionId,
                                    String failureCode,
                                    String failureMessage,
                                    LocalDateTime completedAt) {
        WorkExecution execution = findExecution(executionId);
        execution.markFailed(failureCode, failureMessage, completedAt);
        return execution;
    }

    @Transactional
    public WorkExecution cancel(UUID executionId, LocalDateTime cancelledAt) {
        WorkExecution execution = findExecution(executionId);
        execution.cancel(cancelledAt);
        return execution;
    }

    @Transactional
    public WorkExecution expire(UUID executionId, LocalDateTime expiredAt) {
        WorkExecution execution = findExecution(executionId);
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
}
