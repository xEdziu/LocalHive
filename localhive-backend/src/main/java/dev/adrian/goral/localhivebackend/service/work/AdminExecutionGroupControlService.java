package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminExecutionGroupControlService {

    private final ExecutionGroupRepository groupRepository;
    private final ExecutionGroupCancellationService cancellationService;
    private final ExecutionGroupSchedulingService schedulingService;
    private final ExecutionGroupMergeOrchestrationService mergeOrchestrationService;

    @Transactional
    public void cancelGroup(UUID executionGroupId, String reason, LocalDateTime cancelledAt) {
        cancellationService.cancelGroup(executionGroupId, reason, cancelledAt);
    }

    @Transactional
    public void reconcileGroup(UUID executionGroupId, LocalDateTime now) {
        ExecutionGroup group = findGroup(executionGroupId);
        LocalDateTime validNow = Objects.requireNonNull(now, "now must not be null.");
        if (group.getStatus() == ExecutionGroupStatus.CANCELLING) {
            cancellationService.finalizeCancellingGroupIfNoActiveChild(group.getId(), validNow);
            return;
        }
        if (cancellationService.isTerminalGroupStatus(group.getStatus())) {
            return;
        }

        schedulingService.reconcileQueuedShards(group.getId(), validNow);
        mergeOrchestrationService.reconcileGroup(group.getId(), validNow);
        schedulingService.scheduleQueuedMerge(group.getId(), validNow);
        schedulingService.refreshGroupStatus(group.getId(), validNow);
    }

    private ExecutionGroup findGroup(UUID executionGroupId) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        return groupRepository.findById(validExecutionGroupId)
                .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
    }
}
