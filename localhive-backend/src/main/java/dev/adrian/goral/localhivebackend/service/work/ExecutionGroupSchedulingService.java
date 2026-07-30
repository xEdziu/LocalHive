package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExecutionGroupSchedulingService {

    private static final String CHILD_FAILURE_CODE = "CHILD_EXECUTION_FAILED";
    private static final String CHILD_FAILURE_MESSAGE = "One or more child executions failed.";
    private static final EnumSet<ExecutionGroupStatus> PRESERVED_GROUP_STATUSES = EnumSet.of(
            ExecutionGroupStatus.CANCELLING,
            ExecutionGroupStatus.CANCELLED,
            ExecutionGroupStatus.EXPIRED
    );
    private static final EnumSet<WorkExecutionStatus> TERMINAL_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.SUCCEEDED,
            WorkExecutionStatus.FAILED,
            WorkExecutionStatus.CANCELLED,
            WorkExecutionStatus.EXPIRED
    );

    private final ExecutionGroupRepository groupRepository;
    private final WorkExecutionRepository executionRepository;
    private final WorkExecutionAssignmentService assignmentService;
    private final WorkerSelectionService workerSelectionService;

    @Transactional
    public void scheduleInitialPass(UUID executionGroupId,
                                    ExecutionAssignmentMode assignmentMode,
                                    UUID preferredWorkerId,
                                    LocalDateTime now) {
        scheduleQueuedShards(
                executionGroupId,
                Objects.requireNonNull(assignmentMode, "assignmentMode must not be null."),
                preferredWorkerId,
                Objects.requireNonNull(now, "now must not be null.")
        );
        refreshGroupStatus(executionGroupId, now);
    }

    @Transactional
    public void afterTerminalChildReport(WorkExecution execution, LocalDateTime now) {
        WorkExecution validExecution = Objects.requireNonNull(execution, "execution must not be null.");
        UUID executionGroupId = validExecution.getExecutionGroupId();
        if (executionGroupId == null) {
            return;
        }

        LocalDateTime validNow = Objects.requireNonNull(now, "now must not be null.");
        refreshGroupStatus(executionGroupId, validNow);
        scheduleQueuedShards(executionGroupId, ExecutionAssignmentMode.AUTO, null, validNow);
        refreshGroupStatus(executionGroupId, validNow);
    }

    @Transactional
    public void refreshGroupStatus(UUID executionGroupId, LocalDateTime now) {
        ExecutionGroup group = findGroup(executionGroupId);
        if (PRESERVED_GROUP_STATUSES.contains(group.getStatus())) {
            return;
        }

        LocalDateTime validNow = Objects.requireNonNull(now, "now must not be null.");
        Map<WorkExecutionStatus, Long> counts = countsByStatus(group.getId());
        long totalExecutions = totalExecutions(counts);
        long succeededExecutions = counts.getOrDefault(WorkExecutionStatus.SUCCEEDED, 0L);
        long terminalExecutions = TERMINAL_EXECUTION_STATUSES.stream()
                .mapToLong(status -> counts.getOrDefault(status, 0L))
                .sum();

        if (totalExecutions < group.getShardCount() || terminalExecutions < totalExecutions) {
            group.markRunning(validNow);
            return;
        }

        if (totalExecutions > 0 && succeededExecutions == totalExecutions) {
            group.markSucceeded(validNow);
            return;
        }

        if (group.getFailurePolicy() == ExecutionGroupFailurePolicy.ALLOW_PARTIAL
                && succeededExecutions > 0) {
            group.markPartiallyFailed(CHILD_FAILURE_CODE, CHILD_FAILURE_MESSAGE, validNow);
            return;
        }

        group.markFailed(CHILD_FAILURE_CODE, CHILD_FAILURE_MESSAGE, validNow);
    }

    private void scheduleQueuedShards(UUID executionGroupId,
                                      ExecutionAssignmentMode firstAssignmentMode,
                                      UUID preferredWorkerId,
                                      LocalDateTime now) {
        findGroup(executionGroupId);
        boolean firstShard = true;
        for (WorkExecution execution : executionRepository.findQueuedShardExecutionsByExecutionGroupId(executionGroupId)) {
            ExecutionAssignmentMode assignmentMode = firstShard ? firstAssignmentMode : ExecutionAssignmentMode.AUTO;
            firstShard = false;

            Worker worker;
            try {
                worker = selectWorker(execution, assignmentMode, preferredWorkerId);
            } catch (WorkerSelectionService.NoEligibleWorkerException e) {
                return;
            }

            assignmentService.assignExecution(
                    execution.getId(),
                    worker.getId(),
                    assignmentMode,
                    now
            );
        }
    }

    private Worker selectWorker(WorkExecution execution,
                                ExecutionAssignmentMode assignmentMode,
                                UUID preferredWorkerId) {
        WorkerSelectionCriteria criteria = selectionCriteria(execution);
        return switch (assignmentMode) {
            case AUTO -> workerSelectionService.selectAuto(criteria);
            case PREFER -> workerSelectionService.selectPreferred(preferredWorkerId, criteria);
            case REQUIRE -> throw new IllegalArgumentException(
                    "REQUIRE assignmentMode is not supported for execution group scheduling in M18."
            );
        };
    }

    private static WorkerSelectionCriteria selectionCriteria(WorkExecution execution) {
        WorkDefinitionVersion definitionVersion = execution.getDefinitionVersion();
        ResourceRequest resources = execution.getResolvedResourceRequest();
        JsonNode configuration = execution.getResolvedConfigurationSnapshot();
        return new WorkerSelectionCriteria(
                definitionVersion.getExecutorId(),
                definitionVersion.getExecutorContractVersion(),
                resources,
                dockerImage(configuration)
        );
    }

    private ExecutionGroup findGroup(UUID executionGroupId) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        return groupRepository.findById(validExecutionGroupId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution group not found: " + validExecutionGroupId
                ));
    }

    private Map<WorkExecutionStatus, Long> countsByStatus(UUID executionGroupId) {
        return executionRepository.countStatusesByExecutionGroupId(executionGroupId)
                .stream()
                .collect(Collectors.toMap(
                        WorkExecutionRepository.ExecutionStatusCountProjection::getStatus,
                        projection -> projection.getExecutionCount() == null ? 0L : projection.getExecutionCount(),
                        Long::sum,
                        () -> new EnumMap<>(WorkExecutionStatus.class)
                ));
    }

    private static long totalExecutions(Map<WorkExecutionStatus, Long> counts) {
        return counts.values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    private static String dockerImage(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            return null;
        }

        JsonNode image = configuration.get("image");
        return image != null && image.isTextual() ? image.textValue() : null;
    }
}
