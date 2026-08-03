package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupArtifactSummaryResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupArtifactsResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupChildExecutionResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupListResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupSummaryResponseDto;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminExecutionGroupQueryService {

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 500;
    private static final EnumSet<WorkExecutionStatus> ACTIVE_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.ASSIGNED,
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );
    private static final EnumSet<WorkExecutionStatus> ACTIVE_CHILD_OBSERVABILITY_STATUSES = EnumSet.of(
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );
    private static final EnumSet<WorkExecutionStatus> TERMINAL_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.SUCCEEDED,
            WorkExecutionStatus.FAILED,
            WorkExecutionStatus.CANCELLED,
            WorkExecutionStatus.EXPIRED
    );
    private static final EnumSet<ExecutionGroupStatus> TERMINAL_GROUP_STATUSES = EnumSet.of(
            ExecutionGroupStatus.SUCCEEDED,
            ExecutionGroupStatus.FAILED,
            ExecutionGroupStatus.PARTIALLY_FAILED,
            ExecutionGroupStatus.CANCELLED,
            ExecutionGroupStatus.EXPIRED
    );
    private static final EnumSet<ExecutionGroupStatus> ACTIONABLE_GROUP_STATUSES = EnumSet.of(
            ExecutionGroupStatus.CREATED,
            ExecutionGroupStatus.SCHEDULING,
            ExecutionGroupStatus.RUNNING,
            ExecutionGroupStatus.MERGING,
            ExecutionGroupStatus.CANCELLING
    );
    private static final Pattern AUTHORIZATION_BEARER = Pattern.compile(
            "(?i)Authorization\\s*:\\s*Bearer\\s+\\S+"
    );
    private static final Pattern API_KEY_HEADER = Pattern.compile(
            "(?i)X-API-KEY\\s*[:=]\\s*\\S+"
    );
    private static final Pattern EXECUTION_LEASE_HEADER = Pattern.compile(
            "(?i)X-EXECUTION-LEASE\\s*[:=]\\s*\\S+"
    );
    private static final Pattern API_KEY_VALUE = Pattern.compile(
            "(?i)apiKey\\s*[:=]\\s*\\S+"
    );
    private static final Pattern LEASE_TOKEN_VALUE = Pattern.compile(
            "(?i)leaseToken\\s*[:=]\\s*\\S+"
    );
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "\\b[A-Za-z]:[\\\\/][^\\s\"']+"
    );
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![\\w.-])/(?:[^\\s\"']+/)*[^\\s\"']+"
    );
    private static final String OUTPUT_SOURCE_MERGE = "MERGE";
    private static final String OUTPUT_SOURCE_SHARDS = "SHARDS";
    private static final String OUTPUT_SOURCE_NONE = "NONE";
    private static final Comparator<ExecutionArtifact> ARTIFACT_COMPARATOR = Comparator
            .comparing(ExecutionArtifact::getRelativePath)
            .thenComparing(ExecutionArtifact::getCreatedAt)
            .thenComparing(executionArtifact -> executionArtifact.getArtifact().getId());
    private static final Comparator<WorkExecution> SHARD_ARTIFACT_COMPARATOR = Comparator
            .comparing(WorkExecution::getShardIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(WorkExecution::getId);
    private static final Comparator<WorkExecution> MERGE_REPRESENTATIVE_COMPARATOR = Comparator
            .comparing(WorkExecution::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(WorkExecution::getId);

    private final ExecutionGroupRepository groupRepository;
    private final WorkExecutionRepository executionRepository;
    private final ExecutionAssignmentRepository assignmentRepository;
    private final ExecutionArtifactRepository executionArtifactRepository;

    @Transactional(readOnly = true)
    public AdminExecutionGroupListResponseDto listGroups(int limit, int offset, ExecutionGroupStatus status) {
        int validLimit = requireValidLimit(limit);
        int validOffset = requireValidOffset(offset);
        long totalCount = groupRepository.countAdminGroups(status);
        List<ExecutionGroup> groups = groupRepository.findAdminGroups(
                status,
                new OffsetLimitPageRequest(validOffset, validLimit)
        );
        if (groups.isEmpty()) {
            return new AdminExecutionGroupListResponseDto(List.of(), validLimit, validOffset, totalCount);
        }

        Map<UUID, Map<WorkExecutionStatus, Long>> counts = countsByGroupId(groups.stream()
                .map(ExecutionGroup::getId)
                .toList());
        List<AdminExecutionGroupSummaryResponseDto> items = groups.stream()
                .map(group -> toSummary(group, counts.getOrDefault(group.getId(), Map.of())))
                .toList();
        return new AdminExecutionGroupListResponseDto(items, validLimit, validOffset, totalCount);
    }

    @Transactional(readOnly = true)
    public Optional<AdminExecutionGroupDetailResponseDto> getGroup(UUID executionGroupId) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        return groupRepository.findById(validExecutionGroupId).map(group -> {
            List<WorkExecution> children = executionRepository.findAdminExecutionsByExecutionGroupId(
                    validExecutionGroupId
            );
            Map<UUID, ExecutionAssignment> assignments = assignmentsByExecutionId(children.stream()
                    .map(WorkExecution::getId)
                    .toList());
            List<ExecutionArtifact> artifacts = outputArtifactsFor(children);
            return toDetail(
                    group,
                    countsByStatus(children),
                    children,
                    assignments,
                    toArtifactSummary(artifacts)
            );
        });
    }

    @Transactional(readOnly = true)
    public Optional<List<AdminExecutionGroupChildExecutionResponseDto>> listChildExecutions(UUID executionGroupId) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        if (!groupRepository.existsById(validExecutionGroupId)) {
            return Optional.empty();
        }

        List<WorkExecution> executions = executionRepository.findAdminExecutionsByExecutionGroupId(
                validExecutionGroupId
        );
        if (executions.isEmpty()) {
            return Optional.of(List.of());
        }

        Map<UUID, ExecutionAssignment> assignments = assignmentsByExecutionId(executions.stream()
                .map(WorkExecution::getId)
                .toList());
        return Optional.of(executions.stream()
                .map(execution -> toChildExecution(
                        execution,
                        assignments.get(execution.getId())
                ))
                .toList());
    }

    @Transactional(readOnly = true)
    public Optional<AdminExecutionGroupArtifactsResponseDto> listGroupArtifacts(UUID executionGroupId) {
        UUID validExecutionGroupId = Objects.requireNonNull(
                executionGroupId,
                "executionGroupId must not be null."
        );
        return groupRepository.findById(validExecutionGroupId).map(group -> {
            List<WorkExecution> children = executionRepository.findAdminExecutionsByExecutionGroupId(
                    validExecutionGroupId
            );
            Map<UUID, ExecutionAssignment> assignments = assignmentsByExecutionId(children.stream()
                    .map(WorkExecution::getId)
                    .toList());
            List<ExecutionArtifact> artifacts = outputArtifactsFor(children);
            return toGroupArtifacts(group, children, assignments, artifacts);
        });
    }

    private static int requireValidLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200.");
        }

        return limit;
    }

    private static int requireValidOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0.");
        }

        return offset;
    }

    private Map<UUID, Map<WorkExecutionStatus, Long>> countsByGroupId(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }

        return executionRepository.countStatusesByExecutionGroupIds(groupIds)
                .stream()
                .collect(Collectors.groupingBy(
                        WorkExecutionRepository.ExecutionGroupStatusCountProjection::getExecutionGroupId,
                        Collectors.toMap(
                                WorkExecutionRepository.ExecutionGroupStatusCountProjection::getStatus,
                                projection -> projection.getExecutionCount() == null ? 0L : projection.getExecutionCount(),
                                Long::sum,
                                () -> new EnumMap<>(WorkExecutionStatus.class)
                        )
                ));
    }

    private Map<UUID, ExecutionAssignment> assignmentsByExecutionId(Collection<UUID> executionIds) {
        if (executionIds.isEmpty()) {
            return Map.of();
        }

        return assignmentRepository.findByExecution_IdIn(executionIds)
                .stream()
                .collect(Collectors.toMap(
                        assignment -> assignment.getExecution().getId(),
                        Function.identity()
                ));
    }

    private List<ExecutionArtifact> outputArtifactsFor(List<WorkExecution> executions) {
        if (executions.isEmpty()) {
            return List.of();
        }

        return executionArtifactRepository.findByExecution_IdInAndArtifact_Kind(
                        executions.stream()
                                .map(WorkExecution::getId)
                                .toList(),
                        ArtifactKind.EXECUTION_OUTPUT
                )
                .stream()
                .sorted(ARTIFACT_COMPARATOR)
                .toList();
    }

    private static AdminExecutionGroupSummaryResponseDto toSummary(
            ExecutionGroup group,
            Map<WorkExecutionStatus, Long> counts
    ) {
        return new AdminExecutionGroupSummaryResponseDto(
                group.getId(),
                group.getDisplayName(),
                group.getStatus().name(),
                group.getMergeMode().name(),
                group.getFailurePolicy().name(),
                group.getShardCount(),
                totalExecutions(counts),
                group.getCreatedAt(),
                group.getUpdatedAt(),
                group.getCompletedAt(),
                group.getCancelledAt()
        );
    }

    private static AdminExecutionGroupDetailResponseDto toDetail(
            ExecutionGroup group,
            Map<WorkExecutionStatus, Long> counts,
            List<WorkExecution> children,
            Map<UUID, ExecutionAssignment> assignments,
            AdminExecutionGroupArtifactSummaryResponseDto artifactSummary
    ) {
        return new AdminExecutionGroupDetailResponseDto(
                group.getId(),
                group.getDisplayName(),
                group.getStatus().name(),
                group.getMergeMode().name(),
                group.getFailurePolicy().name(),
                group.getShardCount(),
                totalExecutions(counts),
                countStatuses(counts, ACTIVE_EXECUTION_STATUSES),
                countStatuses(counts, TERMINAL_EXECUTION_STATUSES),
                stringCounts(counts),
                group.getCreatedAt(),
                group.getUpdatedAt(),
                group.getCompletedAt(),
                group.getCancelledAt(),
                safeText(group.getFailureCode()),
                safeFailureMessage(group.getFailureMessage()),
                toObservability(group, children, assignments),
                artifactSummary
        );
    }

    private static AdminExecutionGroupArtifactsResponseDto toGroupArtifacts(
            ExecutionGroup group,
            List<WorkExecution> children,
            Map<UUID, ExecutionAssignment> assignments,
            List<ExecutionArtifact> artifacts
    ) {
        AdminExecutionGroupArtifactSummaryResponseDto artifactSummary = toArtifactSummary(artifacts);
        Map<UUID, List<ExecutionArtifact>> artifactsByExecutionId = artifactsByExecutionId(artifacts);
        List<WorkExecution> shards = children.stream()
                .filter(execution -> execution.getGroupRole() == WorkExecutionGroupRole.SHARD)
                .sorted(SHARD_ARTIFACT_COMPARATOR)
                .toList();
        List<WorkExecution> mergeExecutions = children.stream()
                .filter(execution -> execution.getGroupRole() == WorkExecutionGroupRole.MERGE)
                .toList();
        WorkExecution representativeMerge = mergeExecutions.stream()
                .min(MERGE_REPRESENTATIVE_COMPARATOR)
                .orElse(null);
        ExecutionAssignment representativeAssignment = representativeMerge == null
                ? null
                : assignments.get(representativeMerge.getId());
        List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> shardArtifacts = artifacts.stream()
                .filter(artifact -> artifact.getExecution().getGroupRole() == WorkExecutionGroupRole.SHARD)
                .map(AdminExecutionGroupQueryService::toGroupArtifact)
                .toList();
        List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> mergeArtifacts = artifacts.stream()
                .filter(artifact -> artifact.getExecution().getGroupRole() == WorkExecutionGroupRole.MERGE)
                .map(AdminExecutionGroupQueryService::toGroupArtifact)
                .toList();
        List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> preferredOutputs =
                preferredOutputs(artifactSummary, shardArtifacts, mergeArtifacts);

        return new AdminExecutionGroupArtifactsResponseDto(
                group.getId(),
                group.getDisplayName(),
                group.getStatus().name(),
                group.getMergeMode().name(),
                group.getFailurePolicy().name(),
                artifactSummary,
                shards.stream()
                        .map(shard -> toShardArtifacts(
                                shard,
                                assignments.get(shard.getId()),
                                artifactsByExecutionId.getOrDefault(shard.getId(), List.of())
                        ))
                        .toList(),
                toMergeArtifacts(
                        mergeExecutions,
                        representativeMerge,
                        representativeAssignment,
                        mergeArtifacts
                ),
                preferredOutputs
        );
    }

    private static AdminExecutionGroupChildExecutionResponseDto toChildExecution(
            WorkExecution execution,
            ExecutionAssignment assignment
    ) {
        return new AdminExecutionGroupChildExecutionResponseDto(
                execution.getId(),
                execution.getStatus().name(),
                assignment == null ? null : assignment.getAssignmentMode().name(),
                assignment == null ? null : assignment.getWorker().getId(),
                assignment == null ? null : assignment.getWorker().getHostname(),
                execution.getGroupRole() == null ? null : execution.getGroupRole().name(),
                execution.getShardIndex(),
                execution.getShardCount(),
                execution.getCreatedAt(),
                executionUpdatedAt(execution),
                execution.getCompletedAt()
        );
    }

    private static AdminExecutionGroupArtifactsResponseDto.ShardArtifactsResponseDto toShardArtifacts(
            WorkExecution shard,
            ExecutionAssignment assignment,
            List<ExecutionArtifact> artifacts
    ) {
        List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> artifactResponses = artifacts.stream()
                .sorted(ARTIFACT_COMPARATOR)
                .map(AdminExecutionGroupQueryService::toGroupArtifact)
                .toList();

        return new AdminExecutionGroupArtifactsResponseDto.ShardArtifactsResponseDto(
                shard.getShardIndex(),
                shard.getShardCount(),
                shard.getId(),
                shard.getStatus().name(),
                assignment == null ? null : assignment.getWorker().getId(),
                assignment == null ? null : assignment.getWorker().getHostname(),
                artifactResponses.size(),
                artifactResponses
        );
    }

    private static AdminExecutionGroupArtifactsResponseDto.MergeArtifactsResponseDto toMergeArtifacts(
            List<WorkExecution> mergeExecutions,
            WorkExecution representativeMerge,
            ExecutionAssignment representativeAssignment,
            List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> artifacts
    ) {
        return new AdminExecutionGroupArtifactsResponseDto.MergeArtifactsResponseDto(
                !mergeExecutions.isEmpty(),
                mergeExecutions.size(),
                representativeMerge == null ? null : representativeMerge.getId(),
                representativeMerge == null ? null : representativeMerge.getStatus().name(),
                representativeAssignment == null ? null : representativeAssignment.getWorker().getId(),
                representativeAssignment == null ? null : representativeAssignment.getWorker().getHostname(),
                artifacts.size(),
                artifacts
        );
    }

    private static AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto toGroupArtifact(
            ExecutionArtifact executionArtifact
    ) {
        Artifact artifact = executionArtifact.getArtifact();
        WorkExecution execution = executionArtifact.getExecution();
        return new AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto(
                artifact.getId(),
                execution.getId(),
                execution.getGroupRole() == null ? null : execution.getGroupRole().name(),
                execution.getShardIndex(),
                executionArtifact.getRelativePath(),
                artifact.getOriginalFilename(),
                artifact.getContentType(),
                artifact.getSizeBytes(),
                artifact.getCreatedAt()
        );
    }

    private static AdminExecutionGroupArtifactSummaryResponseDto toArtifactSummary(
            List<ExecutionArtifact> artifacts
    ) {
        long shardArtifacts = artifacts.stream()
                .filter(artifact -> artifact.getExecution().getGroupRole() == WorkExecutionGroupRole.SHARD)
                .count();
        long mergeArtifacts = artifacts.stream()
                .filter(artifact -> artifact.getExecution().getGroupRole() == WorkExecutionGroupRole.MERGE)
                .count();
        long shardsWithArtifacts = artifacts.stream()
                .filter(artifact -> artifact.getExecution().getGroupRole() == WorkExecutionGroupRole.SHARD)
                .map(artifact -> artifact.getExecution().getShardIndex())
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return new AdminExecutionGroupArtifactSummaryResponseDto(
                artifacts.size(),
                shardArtifacts,
                mergeArtifacts,
                shardsWithArtifacts,
                mergeArtifacts > 0,
                preferredOutputSource(shardArtifacts, mergeArtifacts)
        );
    }

    private static Map<UUID, List<ExecutionArtifact>> artifactsByExecutionId(List<ExecutionArtifact> artifacts) {
        return artifacts.stream()
                .collect(Collectors.groupingBy(
                        artifact -> artifact.getExecution().getId(),
                        Collectors.toList()
                ));
    }

    private static List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> preferredOutputs(
            AdminExecutionGroupArtifactSummaryResponseDto artifactSummary,
            List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> shardArtifacts,
            List<AdminExecutionGroupArtifactsResponseDto.GroupArtifactResponseDto> mergeArtifacts
    ) {
        if (OUTPUT_SOURCE_MERGE.equals(artifactSummary.preferredOutputSource())) {
            return mergeArtifacts;
        }
        if (OUTPUT_SOURCE_SHARDS.equals(artifactSummary.preferredOutputSource())) {
            return shardArtifacts;
        }

        return List.of();
    }

    private static String preferredOutputSource(long shardArtifacts, long mergeArtifacts) {
        if (mergeArtifacts > 0) {
            return OUTPUT_SOURCE_MERGE;
        }
        if (shardArtifacts > 0) {
            return OUTPUT_SOURCE_SHARDS;
        }

        return OUTPUT_SOURCE_NONE;
    }

    private static long totalExecutions(Map<WorkExecutionStatus, Long> counts) {
        return counts.values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    private static Map<WorkExecutionStatus, Long> countsByStatus(List<WorkExecution> executions) {
        return executions.stream()
                .collect(Collectors.groupingBy(
                        WorkExecution::getStatus,
                        () -> new EnumMap<>(WorkExecutionStatus.class),
                        Collectors.counting()
                ));
    }

    private static long countStatuses(
            Map<WorkExecutionStatus, Long> counts,
            Collection<WorkExecutionStatus> statuses
    ) {
        return statuses.stream()
                .mapToLong(status -> counts.getOrDefault(status, 0L))
                .sum();
    }

    private static Map<String, Long> stringCounts(Map<WorkExecutionStatus, Long> counts) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (WorkExecutionStatus status : WorkExecutionStatus.values()) {
            Long count = counts.get(status);
            if (count != null) {
                result.put(status.name(), count);
            }
        }
        return result;
    }

    private static AdminExecutionGroupDetailResponseDto.ObservabilityResponseDto toObservability(
            ExecutionGroup group,
            List<WorkExecution> children,
            Map<UUID, ExecutionAssignment> assignments
    ) {
        Map<WorkExecutionStatus, Long> shardCounts = countsByStatus(children, WorkExecutionGroupRole.SHARD);
        Map<WorkExecutionStatus, Long> mergeCounts = countsByStatus(children, WorkExecutionGroupRole.MERGE);
        List<WorkExecution> mergeExecutions = children.stream()
                .filter(execution -> execution.getGroupRole() == WorkExecutionGroupRole.MERGE)
                .toList();
        WorkExecution representativeMerge = mergeExecutions.stream()
                .min(Comparator.comparing(
                                WorkExecution::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(WorkExecution::getId))
                .orElse(null);
        ExecutionAssignment representativeAssignment = representativeMerge == null
                ? null
                : assignments.get(representativeMerge.getId());

        return new AdminExecutionGroupDetailResponseDto.ObservabilityResponseDto(
                TERMINAL_GROUP_STATUSES.contains(group.getStatus()),
                group.getStatus() == ExecutionGroupStatus.CANCELLING,
                countStatuses(countsByStatus(children), ACTIVE_CHILD_OBSERVABILITY_STATUSES) > 0,
                countStatus(children, WorkExecutionStatus.QUEUED) > 0,
                ACTIONABLE_GROUP_STATUSES.contains(group.getStatus()),
                ACTIONABLE_GROUP_STATUSES.contains(group.getStatus()),
                toChildRoleCounts(shardCounts),
                toMergeObservability(mergeExecutions, representativeMerge, representativeAssignment, mergeCounts)
        );
    }

    private static AdminExecutionGroupDetailResponseDto.MergeObservabilityResponseDto toMergeObservability(
            List<WorkExecution> mergeExecutions,
            WorkExecution representativeMerge,
            ExecutionAssignment representativeAssignment,
            Map<WorkExecutionStatus, Long> counts
    ) {
        return new AdminExecutionGroupDetailResponseDto.MergeObservabilityResponseDto(
                !mergeExecutions.isEmpty(),
                representativeMerge == null ? null : representativeMerge.getId(),
                representativeMerge == null ? null : representativeMerge.getStatus().name(),
                representativeAssignment == null ? null : representativeAssignment.getWorker().getId(),
                representativeAssignment == null ? null : representativeAssignment.getWorker().getHostname(),
                totalExecutions(counts),
                countStatus(counts, WorkExecutionStatus.QUEUED),
                countStatus(counts, WorkExecutionStatus.ASSIGNED),
                countStatus(counts, WorkExecutionStatus.CLAIMED),
                countStatus(counts, WorkExecutionStatus.RUNNING),
                countStatus(counts, WorkExecutionStatus.SUCCEEDED),
                countStatus(counts, WorkExecutionStatus.FAILED),
                countStatus(counts, WorkExecutionStatus.CANCELLED),
                countStatus(counts, WorkExecutionStatus.EXPIRED),
                countStatuses(counts, TERMINAL_EXECUTION_STATUSES),
                totalExecutions(counts) - countStatuses(counts, TERMINAL_EXECUTION_STATUSES)
        );
    }

    private static AdminExecutionGroupDetailResponseDto.ChildRoleCountsResponseDto toChildRoleCounts(
            Map<WorkExecutionStatus, Long> counts
    ) {
        return new AdminExecutionGroupDetailResponseDto.ChildRoleCountsResponseDto(
                totalExecutions(counts),
                countStatus(counts, WorkExecutionStatus.QUEUED),
                countStatus(counts, WorkExecutionStatus.ASSIGNED),
                countStatus(counts, WorkExecutionStatus.CLAIMED),
                countStatus(counts, WorkExecutionStatus.RUNNING),
                countStatus(counts, WorkExecutionStatus.SUCCEEDED),
                countStatus(counts, WorkExecutionStatus.FAILED),
                countStatus(counts, WorkExecutionStatus.CANCELLED),
                countStatus(counts, WorkExecutionStatus.EXPIRED),
                countStatuses(counts, TERMINAL_EXECUTION_STATUSES),
                totalExecutions(counts) - countStatuses(counts, TERMINAL_EXECUTION_STATUSES)
        );
    }

    private static Map<WorkExecutionStatus, Long> countsByStatus(
            List<WorkExecution> executions,
            WorkExecutionGroupRole role
    ) {
        return executions.stream()
                .filter(execution -> execution.getGroupRole() == role)
                .collect(Collectors.groupingBy(
                        WorkExecution::getStatus,
                        () -> new EnumMap<>(WorkExecutionStatus.class),
                        Collectors.counting()
                ));
    }

    private static long countStatus(List<WorkExecution> executions, WorkExecutionStatus status) {
        return executions.stream()
                .filter(execution -> execution.getStatus() == status)
                .count();
    }

    private static long countStatus(Map<WorkExecutionStatus, Long> counts, WorkExecutionStatus status) {
        return counts.getOrDefault(status, 0L);
    }

    private static LocalDateTime executionUpdatedAt(WorkExecution execution) {
        LocalDateTime latest = execution.getCreatedAt();
        latest = latestTimestamp(latest, execution.getQueuedAt());
        latest = latestTimestamp(latest, execution.getAssignedAt());
        latest = latestTimestamp(latest, execution.getClaimedAt());
        latest = latestTimestamp(latest, execution.getStartedAt());
        latest = latestTimestamp(latest, execution.getCompletedAt());
        latest = latestTimestamp(latest, execution.getCancelledAt());
        latest = latestTimestamp(latest, execution.getExpiredAt());
        return latest;
    }

    private static LocalDateTime latestTimestamp(LocalDateTime current, LocalDateTime candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }

        return current;
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private static String safeFailureMessage(String value) {
        String normalized = safeText(value);
        if (normalized == null) {
            return null;
        }

        String redacted = AUTHORIZATION_BEARER.matcher(normalized)
                .replaceAll("Authorization: Bearer <redacted>");
        redacted = API_KEY_HEADER.matcher(redacted)
                .replaceAll("X-API-KEY=<redacted>");
        redacted = EXECUTION_LEASE_HEADER.matcher(redacted)
                .replaceAll("X-EXECUTION-LEASE=<redacted>");
        redacted = API_KEY_VALUE.matcher(redacted)
                .replaceAll("apiKey=<redacted>");
        redacted = LEASE_TOKEN_VALUE.matcher(redacted)
                .replaceAll("leaseToken=<redacted>");
        redacted = WINDOWS_ABSOLUTE_PATH.matcher(redacted)
                .replaceAll("<redacted-path>");
        redacted = UNIX_ABSOLUTE_PATH.matcher(redacted)
                .replaceAll("<redacted-path>");

        return redacted.length() > MAX_FAILURE_MESSAGE_LENGTH
                ? redacted.substring(0, MAX_FAILURE_MESSAGE_LENGTH)
                : redacted;
    }

    private record OffsetLimitPageRequest(int offset, int limit) implements Pageable {

        @Override
        public int getPageNumber() {
            return offset / limit;
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return Sort.unsorted();
        }

        @Override
        public Pageable next() {
            return new OffsetLimitPageRequest(offset + limit, limit);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious()
                    ? new OffsetLimitPageRequest(Math.max(0, offset - limit), limit)
                    : first();
        }

        @Override
        public Pageable first() {
            return new OffsetLimitPageRequest(0, limit);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            if (pageNumber < 0) {
                throw new IllegalArgumentException("pageNumber must be greater than or equal to 0.");
            }

            return new OffsetLimitPageRequest(pageNumber * limit, limit);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }
}
