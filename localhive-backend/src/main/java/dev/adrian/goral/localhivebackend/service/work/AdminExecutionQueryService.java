package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionListResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionSummaryResponseDto;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
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
public class AdminExecutionQueryService {

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 500;
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

    private final WorkExecutionRepository executionRepository;
    private final ExecutionAssignmentRepository assignmentRepository;
    private final ExecutionArtifactRepository artifactRepository;

    @Transactional(readOnly = true)
    public AdminExecutionListResponseDto listExecutions(
            int limit,
            int offset,
            WorkExecutionStatus status,
            UUID workerId
    ) {
        int validLimit = requireValidLimit(limit);
        int validOffset = requireValidOffset(offset);
        long totalCount = executionRepository.countAdminExecutions(status, workerId);
        List<WorkExecution> executions = executionRepository.findAdminExecutions(
                status,
                workerId,
                new OffsetLimitPageRequest(validOffset, validLimit)
        );
        if (executions.isEmpty()) {
            return new AdminExecutionListResponseDto(List.of(), validLimit, validOffset, totalCount);
        }

        List<UUID> executionIds = executions.stream()
                .map(WorkExecution::getId)
                .toList();
        Map<UUID, ExecutionAssignment> assignments = assignmentsByExecutionId(executionIds);
        Map<UUID, Long> artifactCounts = outputArtifactCounts(executionIds);

        List<AdminExecutionSummaryResponseDto> items = executions.stream()
                .map(execution -> toSummary(
                        execution,
                        assignments.get(execution.getId()),
                        artifactCounts.getOrDefault(execution.getId(), 0L)
                ))
                .toList();
        return new AdminExecutionListResponseDto(items, validLimit, validOffset, totalCount);
    }

    @Transactional(readOnly = true)
    public Optional<AdminExecutionDetailResponseDto> getExecution(UUID executionId) {
        UUID validExecutionId = Objects.requireNonNull(executionId, "executionId must not be null.");
        return executionRepository.findAdminExecutionById(validExecutionId)
                .map(execution -> toDetail(
                        execution,
                        assignmentRepository.findByExecution_Id(validExecutionId).orElse(null),
                        artifactRepository.countByExecution_IdAndArtifact_Kind(
                                validExecutionId,
                                ArtifactKind.EXECUTION_OUTPUT
                        )
                ));
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

    private Map<UUID, ExecutionAssignment> assignmentsByExecutionId(Collection<UUID> executionIds) {
        return assignmentRepository.findByExecution_IdIn(executionIds)
                .stream()
                .collect(Collectors.toMap(
                        assignment -> assignment.getExecution().getId(),
                        Function.identity()
                ));
    }

    private Map<UUID, Long> outputArtifactCounts(Collection<UUID> executionIds) {
        return artifactRepository.countByExecutionIdsAndArtifactKind(
                        executionIds,
                        ArtifactKind.EXECUTION_OUTPUT
                )
                .stream()
                .collect(Collectors.toMap(
                        ExecutionArtifactRepository.ExecutionArtifactCountProjection::getExecutionId,
                        projection -> projection.getArtifactCount() == null ? 0L : projection.getArtifactCount()
                ));
    }

    private static AdminExecutionSummaryResponseDto toSummary(WorkExecution execution,
                                                              ExecutionAssignment assignment,
                                                              long outputArtifactCount) {
        WorkDefinitionVersion version = execution.getDefinitionVersion();
        WorkDefinition definition = version.getDefinition();

        return new AdminExecutionSummaryResponseDto(
                execution.getId(),
                execution.getDisplayNameSnapshot(),
                execution.getStatus().name(),
                version.getExecutorId(),
                version.getExecutorContractVersion(),
                definition.getLogicalIdentifier(),
                version.getVersionNumber(),
                execution.getExecutionGroupId(),
                execution.getGroupRole() == null ? null : execution.getGroupRole().name(),
                execution.getShardIndex(),
                execution.getShardCount(),
                assignment == null ? null : assignment.getWorker().getId(),
                assignment == null ? null : assignment.getWorker().getHostname(),
                execution.getCreatedAt(),
                execution.getAssignedAt(),
                execution.getClaimedAt(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                durationMs(execution),
                outputArtifactCount
        );
    }

    private static AdminExecutionDetailResponseDto toDetail(WorkExecution execution,
                                                            ExecutionAssignment assignment,
                                                            long outputArtifactCount) {
        WorkDefinitionVersion version = execution.getDefinitionVersion();
        WorkDefinition definition = version.getDefinition();
        WorkInstance instance = execution.getInstance();

        return new AdminExecutionDetailResponseDto(
                execution.getId(),
                execution.getDisplayNameSnapshot(),
                execution.getStatus().name(),
                version.getExecutorId(),
                version.getExecutorContractVersion(),
                new AdminExecutionDetailResponseDto.GroupMetadataDto(
                        execution.getExecutionGroupId(),
                        execution.getGroupRole() == null ? null : execution.getGroupRole().name(),
                        execution.getShardIndex(),
                        execution.getShardCount()
                ),
                new AdminExecutionDetailResponseDto.WorkDefinitionDto(
                        definition.getId(),
                        version.getId(),
                        definition.getLogicalIdentifier(),
                        version.getVersionNumber(),
                        version.getName()
                ),
                instance == null ? null : new AdminExecutionDetailResponseDto.WorkInstanceDto(
                        instance.getId(),
                        instance.getDisplayName()
                ),
                assignment == null ? null : new AdminExecutionDetailResponseDto.AssignmentDto(
                        assignment.getId(),
                        assignment.getWorker().getId(),
                        assignment.getWorker().getHostname(),
                        assignment.getAssignmentMode().name(),
                        assignment.getAssignedAt(),
                        assignment.getClaimedAt()
                ),
                new AdminExecutionDetailResponseDto.TimingDto(
                        execution.getCreatedAt(),
                        execution.getQueuedAt(),
                        execution.getAssignedAt(),
                        execution.getClaimedAt(),
                        execution.getStartedAt(),
                        execution.getCompletedAt(),
                        execution.getCancelledAt(),
                        execution.getExpiredAt(),
                        durationMs(execution)
                ),
                new AdminExecutionDetailResponseDto.ArtifactsDto(outputArtifactCount),
                new AdminExecutionDetailResponseDto.FailureDto(
                        safeText(execution.getFailureCode()),
                        safeFailureMessage(execution.getFailureMessage())
                )
        );
    }

    private static Long durationMs(WorkExecution execution) {
        if (execution.getStartedAt() == null || execution.getCompletedAt() == null) {
            return null;
        }

        return Math.max(0, Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis());
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
