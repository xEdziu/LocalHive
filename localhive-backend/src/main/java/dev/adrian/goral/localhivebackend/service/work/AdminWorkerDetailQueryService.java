package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.dto.AdminWorkerDetailResponseDto;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminWorkerDetailQueryService {

    private static final int RECENT_EXECUTION_LIMIT = 5;
    private static final EnumSet<WorkExecutionStatus> ACTIVE_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.ASSIGNED,
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );

    private final WorkerRepository workerRepository;
    private final WorkExecutionRepository executionRepository;
    private final ExecutionArtifactRepository artifactRepository;

    @Transactional(readOnly = true)
    public Optional<AdminWorkerDetailResponseDto> getWorkerDetail(UUID workerId) {
        UUID validWorkerId = Objects.requireNonNull(workerId, "workerId must not be null.");
        return workerRepository.findById(validWorkerId)
                .map(this::toDetail);
    }

    private AdminWorkerDetailResponseDto toDetail(Worker worker) {
        List<WorkExecution> currentCandidates = executionRepository.findAdminExecutionsByWorkerIdAndStatusIn(
                worker.getId(),
                ACTIVE_EXECUTION_STATUSES,
                PageRequest.of(0, 1)
        );
        WorkExecution currentExecution = currentCandidates.isEmpty() ? null : currentCandidates.get(0);

        List<WorkExecution> recentExecutions = executionRepository.findAdminExecutions(
                null,
                worker.getId(),
                PageRequest.of(0, RECENT_EXECUTION_LIMIT)
        );
        WorkExecution lastExecution = recentExecutions.isEmpty() ? null : recentExecutions.get(0);
        Map<UUID, Long> artifactCounts = outputArtifactCounts(currentExecution, lastExecution, recentExecutions);

        return new AdminWorkerDetailResponseDto(
                worker.getId(),
                worker.getHostname(),
                worker.getIpAddress(),
                worker.getOsType(),
                new AdminWorkerDetailResponseDto.HardwareDto(
                        worker.getTotalRamMb(),
                        worker.getSharedRamMb(),
                        worker.getCpuCores(),
                        worker.getGpuName()
                ),
                new AdminWorkerDetailResponseDto.StatusDto(
                        worker.getApprovalStatus(),
                        worker.getConnectionStatus(),
                        worker.getAvailabilityStatus()
                ),
                new AdminWorkerDetailResponseDto.HeartbeatDto(
                        worker.getLastHeartbeatAt(),
                        worker.getLastHeartbeatAt(),
                        WorkerAvailabilityStatus.PAUSED.equals(worker.getAvailabilityStatus())
                ),
                toExecutionSummary(currentExecution, artifactCounts),
                toExecutionSummary(lastExecution, artifactCounts),
                recentExecutions.stream()
                        .map(execution -> toExecutionSummary(execution, artifactCounts))
                        .toList()
        );
    }

    private Map<UUID, Long> outputArtifactCounts(WorkExecution currentExecution,
                                                 WorkExecution lastExecution,
                                                 List<WorkExecution> recentExecutions) {
        LinkedHashSet<UUID> executionIds = new LinkedHashSet<>();
        addExecutionId(executionIds, currentExecution);
        addExecutionId(executionIds, lastExecution);
        recentExecutions.forEach(execution -> addExecutionId(executionIds, execution));
        if (executionIds.isEmpty()) {
            return Map.of();
        }

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

    private static void addExecutionId(Collection<UUID> executionIds, WorkExecution execution) {
        if (execution != null) {
            executionIds.add(execution.getId());
        }
    }

    private static AdminWorkerDetailResponseDto.ExecutionSummaryDto toExecutionSummary(
            WorkExecution execution,
            Map<UUID, Long> artifactCounts
    ) {
        if (execution == null) {
            return null;
        }

        WorkDefinitionVersion version = execution.getDefinitionVersion();
        return new AdminWorkerDetailResponseDto.ExecutionSummaryDto(
                execution.getId(),
                execution.getDisplayNameSnapshot(),
                execution.getStatus().name(),
                version.getExecutorId(),
                version.getExecutorContractVersion(),
                execution.getCreatedAt(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                durationMs(execution),
                artifactCounts.getOrDefault(execution.getId(), 0L)
        );
    }

    private static Long durationMs(WorkExecution execution) {
        if (execution.getStartedAt() == null || execution.getCompletedAt() == null) {
            return null;
        }

        return Math.max(0, Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis());
    }
}
