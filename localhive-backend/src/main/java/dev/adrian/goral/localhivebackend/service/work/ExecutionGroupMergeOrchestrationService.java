package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroupMergePlan;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupMergePlanRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExecutionGroupMergeOrchestrationService {

    private static final String MERGE_PLAN_MISSING_CODE = "MERGE_PLAN_MISSING";
    private static final String MERGE_PLAN_MISSING_MESSAGE = "Execution group merge plan is missing.";
    private static final String MERGE_PREPARATION_FAILED_CODE = "MERGE_PREPARATION_FAILED";
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 500;
    private static final EnumSet<WorkExecutionStatus> TERMINAL_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.SUCCEEDED,
            WorkExecutionStatus.FAILED,
            WorkExecutionStatus.CANCELLED,
            WorkExecutionStatus.EXPIRED
    );

    private final ExecutionGroupRepository groupRepository;
    private final ExecutionGroupMergePlanRepository mergePlanRepository;
    private final WorkExecutionRepository executionRepository;
    private final WorkExecutionCreationService executionCreationService;
    private final AdminExecutionGroupCreationService groupCreationService;
    private final DockerWorkloadConfigurationValidator dockerConfigurationValidator;
    private final ExecutionGroupMergeWorkspaceService mergeWorkspaceService;
    private final ExecutionGroupSchedulingService schedulingService;

    @Transactional
    public void afterTerminalChildReport(WorkExecution execution, LocalDateTime now) {
        WorkExecution validExecution = Objects.requireNonNull(execution, "execution must not be null.");
        UUID executionGroupId = validExecution.getExecutionGroupId();
        if (executionGroupId == null || validExecution.getGroupRole() != WorkExecutionGroupRole.SHARD) {
            return;
        }

        createMergeIfReady(executionGroupId, now);
    }

    @Transactional
    public void reconcileGroup(UUID executionGroupId, LocalDateTime now) {
        createMergeIfReady(executionGroupId, now);
    }

    private void createMergeIfReady(UUID executionGroupId, LocalDateTime now) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        LocalDateTime validNow = Objects.requireNonNull(now, "now must not be null.");
        ExecutionGroup group = groupRepository.findById(executionGroupId)
                .orElseThrow(() -> new IllegalStateException("Execution group not found: " + validExecutionGroupId));
        if (group.getMergeMode() != ExecutionGroupMergeMode.AGENT || group.getStatus() != ExecutionGroupStatus.MERGING) {
            return;
        }
        if (!executionRepository.findByExecutionGroupIdAndGroupRole(validExecutionGroupId, WorkExecutionGroupRole.MERGE)
                .isEmpty()) {
            return;
        }

        List<WorkExecution> shards = executionRepository.findByExecutionGroupIdAndGroupRole(
                validExecutionGroupId,
                WorkExecutionGroupRole.SHARD
        );
        if (!isShardPhaseReady(group, shards)) {
            return;
        }

        List<WorkExecution> successfulShards = shards.stream()
                .filter(shard -> shard.getStatus() == WorkExecutionStatus.SUCCEEDED)
                .toList();
        if (successfulShards.isEmpty()) {
            return;
        }

        ExecutionGroupMergePlan mergePlan = mergePlanRepository.findById(executionGroupId).orElse(null);
        if (mergePlan == null) {
            group.markFailed(MERGE_PLAN_MISSING_CODE, MERGE_PLAN_MISSING_MESSAGE, validNow);
            return;
        }

        try {
            Artifact derivedWorkspace = mergeWorkspaceService.createDerivedWorkspacePackage(
                    group,
                    mergePlan,
                    successfulShards
            );
            ObjectNode mergeConfiguration = groupCreationService.mergeConfiguration(
                    (ObjectNode) mergePlan.getConfigurationTemplate(),
                    group.getId(),
                    group.getShardCount(),
                    derivedWorkspace.getId()
            );
            DockerWorkloadConfiguration.Validated validated =
                    dockerConfigurationValidator.validateAdminConfiguration(mergeConfiguration);
            WorkExecution mergeExecution = executionCreationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                    mergePlan.getDefinitionVersion().getId(),
                    mergeConfiguration,
                    ResourceRequestOverrides.of(
                            validated.memoryMb(),
                            validated.cpuCores(),
                            false
                    ),
                    AdminExecutionGroupCreationService.mergeDisplayName(group.getDisplayName())
            ));
            mergeExecution.attachToGroupAsMerge(group, group.getShardCount());
            executionRepository.save(mergeExecution);
            schedulingService.scheduleQueuedMerge(group.getId(), validNow);
            schedulingService.refreshGroupStatus(group.getId(), validNow);
        } catch (RuntimeException e) {
            group.markFailed(MERGE_PREPARATION_FAILED_CODE, safeFailureMessage(e), validNow);
        }
    }

    private static boolean isShardPhaseReady(ExecutionGroup group, List<WorkExecution> shards) {
        return shards.size() >= group.getShardCount()
                && shards.stream().allMatch(shard -> TERMINAL_EXECUTION_STATUSES.contains(shard.getStatus()));
    }

    private static String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Merge preparation failed.";
        }

        String trimmed = message.trim();
        return trimmed.length() > MAX_FAILURE_MESSAGE_LENGTH
                ? trimmed.substring(0, MAX_FAILURE_MESSAGE_LENGTH)
                : trimmed;
    }
}
