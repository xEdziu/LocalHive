package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExecutionGroupCancellationService {

    public static final String ADMIN_GROUP_CANCELLED_FAILURE_CODE = "ADMIN_GROUP_CANCELLED";
    public static final String DEFAULT_GROUP_CANCELLATION_MESSAGE = "Execution group cancelled by admin.";
    public static final int MAX_REASON_LENGTH = AdminExecutionCancelService.MAX_REASON_LENGTH;

    private static final EnumSet<ExecutionGroupStatus> TERMINAL_GROUP_STATUSES = EnumSet.of(
            ExecutionGroupStatus.SUCCEEDED,
            ExecutionGroupStatus.FAILED,
            ExecutionGroupStatus.PARTIALLY_FAILED,
            ExecutionGroupStatus.CANCELLED,
            ExecutionGroupStatus.EXPIRED
    );
    private static final EnumSet<WorkExecutionStatus> ACTIVE_UNINTERRUPTIBLE_STATUSES = EnumSet.of(
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );

    private final ExecutionGroupRepository groupRepository;
    private final WorkExecutionRepository executionRepository;

    @Transactional
    public void cancelGroup(UUID executionGroupId, String reason, LocalDateTime cancelledAt) {
        ExecutionGroup group = findGroup(executionGroupId);
        requireCancellableStatus(group);

        LocalDateTime validCancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt must not be null.");
        String cancellationMessage = cancellationMessage(reason);
        List<WorkExecution> children = executionRepository.findAdminExecutionsByExecutionGroupId(group.getId());
        for (WorkExecution child : children) {
            if (child.getStatus() == WorkExecutionStatus.QUEUED
                    || child.getStatus() == WorkExecutionStatus.ASSIGNED) {
                child.cancelBeforeStart(
                        AdminExecutionCancelService.ADMIN_CANCELLED_FAILURE_CODE,
                        cancellationMessage,
                        validCancelledAt
                );
            }
        }

        if (hasActiveUninterruptibleChild(children)) {
            group.markCancelling(
                    ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                    cancellationMessage,
                    validCancelledAt
            );
            return;
        }

        group.markCancelled(
                ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                cancellationMessage,
                validCancelledAt
        );
    }

    @Transactional
    public boolean finalizeCancellingGroupIfNoActiveChild(UUID executionGroupId, LocalDateTime now) {
        ExecutionGroup group = findGroup(executionGroupId);
        if (group.getStatus() != ExecutionGroupStatus.CANCELLING) {
            return false;
        }

        List<WorkExecution> children = executionRepository.findAdminExecutionsByExecutionGroupId(group.getId());
        if (hasActiveUninterruptibleChild(children)) {
            return false;
        }

        group.markCancelled(
                safeCancellationCode(group),
                safeCancellationMessage(group),
                Objects.requireNonNull(now, "now must not be null.")
        );
        return true;
    }

    public boolean isTerminalGroupStatus(ExecutionGroupStatus status) {
        return TERMINAL_GROUP_STATUSES.contains(Objects.requireNonNull(status, "status must not be null."));
    }

    private ExecutionGroup findGroup(UUID executionGroupId) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        return groupRepository.findById(validExecutionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }

    private static void requireCancellableStatus(ExecutionGroup group) {
        ExecutionGroupStatus status = group.getStatus();
        if (TERMINAL_GROUP_STATUSES.contains(status)) {
            throw new IllegalStateException("Cannot cancel execution group from status " + status + ".");
        }
    }

    private static boolean hasActiveUninterruptibleChild(List<WorkExecution> children) {
        return children.stream()
                .anyMatch(child -> ACTIVE_UNINTERRUPTIBLE_STATUSES.contains(child.getStatus()));
    }

    private static String cancellationMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_GROUP_CANCELLATION_MESSAGE;
        }

        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "reason must be less than or equal to " + MAX_REASON_LENGTH + " characters."
            );
        }

        return trimmed;
    }

    private static String safeCancellationCode(ExecutionGroup group) {
        String failureCode = group.getFailureCode();
        return failureCode == null || failureCode.isBlank()
                ? ADMIN_GROUP_CANCELLED_FAILURE_CODE
                : failureCode;
    }

    private static String safeCancellationMessage(ExecutionGroup group) {
        String failureMessage = group.getFailureMessage();
        return failureMessage == null || failureMessage.isBlank()
                ? DEFAULT_GROUP_CANCELLATION_MESSAGE
                : failureMessage;
    }
}
