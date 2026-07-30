package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupChildExecutionResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupListResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionGroupSummaryResponseDto;
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
    private static final EnumSet<WorkExecutionStatus> TERMINAL_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.SUCCEEDED,
            WorkExecutionStatus.FAILED,
            WorkExecutionStatus.CANCELLED,
            WorkExecutionStatus.EXPIRED
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

    private final ExecutionGroupRepository groupRepository;
    private final WorkExecutionRepository executionRepository;
    private final ExecutionAssignmentRepository assignmentRepository;

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
        return groupRepository.findById(validExecutionGroupId)
                .map(group -> toDetail(
                        group,
                        countsByStatus(validExecutionGroupId)
                ));
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

    private Map<UUID, ExecutionAssignment> assignmentsByExecutionId(Collection<UUID> executionIds) {
        return assignmentRepository.findByExecution_IdIn(executionIds)
                .stream()
                .collect(Collectors.toMap(
                        assignment -> assignment.getExecution().getId(),
                        Function.identity()
                ));
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
            Map<WorkExecutionStatus, Long> counts
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
                safeFailureMessage(group.getFailureMessage())
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

    private static long totalExecutions(Map<WorkExecutionStatus, Long> counts) {
        return counts.values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
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
