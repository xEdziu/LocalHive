package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminExecutionCancelService {

    public static final String ADMIN_CANCELLED_FAILURE_CODE = "ADMIN_CANCELLED";
    public static final String DEFAULT_CANCELLATION_MESSAGE = "Execution cancelled by admin.";
    public static final int MAX_REASON_LENGTH = 500;

    private final WorkExecutionRepository executionRepository;

    @Transactional
    public WorkExecution cancelExecution(UUID executionId, String reason, LocalDateTime cancelledAt) {
        WorkExecution execution = executionRepository.findById(requireExecutionId(executionId))
                .orElseThrow(() -> new NoSuchElementException("Execution not found."));
        requireCancellableStatus(execution);
        execution.cancelBeforeStart(
                ADMIN_CANCELLED_FAILURE_CODE,
                cancellationMessage(reason),
                requireCancelledAt(cancelledAt)
        );
        return execution;
    }

    private static UUID requireExecutionId(UUID executionId) {
        if (executionId == null) {
            throw new IllegalArgumentException("executionId must not be null.");
        }

        return executionId;
    }

    private static LocalDateTime requireCancelledAt(LocalDateTime cancelledAt) {
        if (cancelledAt == null) {
            throw new IllegalArgumentException("cancelledAt must not be null.");
        }

        return cancelledAt;
    }

    private static void requireCancellableStatus(WorkExecution execution) {
        WorkExecutionStatus status = execution.getStatus();
        if (status == WorkExecutionStatus.QUEUED || status == WorkExecutionStatus.ASSIGNED) {
            return;
        }

        throw new IllegalStateException("Cannot cancel execution from status " + status + ".");
    }

    private static String cancellationMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_CANCELLATION_MESSAGE;
        }

        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "reason must be less than or equal to " + MAX_REASON_LENGTH + " characters."
            );
        }

        return trimmed;
    }
}
