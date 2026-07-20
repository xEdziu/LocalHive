package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkerSelectionService {

    private static final Set<WorkExecutionStatus> ACTIVE_EXECUTION_STATUSES = EnumSet.of(
            WorkExecutionStatus.ASSIGNED,
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );

    private static final Comparator<WorkerCandidate> CANDIDATE_ORDER = Comparator
            .comparingInt(WorkerCandidate::memoryHeadroomMb)
            .reversed()
            .thenComparing(Comparator.comparingInt(WorkerCandidate::cpuHeadroom).reversed())
            .thenComparing(WorkerCandidate::latestAssignedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(candidate -> candidate.worker().getId());

    private final WorkerRepository workerRepository;
    private final ExecutionAssignmentRepository assignmentRepository;

    public Worker selectAuto(ResourceRequest requestedResources) {
        return selectBestEligibleWorker(requestedResources)
                .orElseThrow(NoEligibleWorkerException::new);
    }

    public Worker selectPreferred(UUID preferredWorkerId, ResourceRequest requestedResources) {
        if (preferredWorkerId == null) {
            throw new IllegalArgumentException("workerId is required for PREFER assignmentMode.");
        }

        Worker preferredWorker = workerRepository.findById(preferredWorkerId)
                .orElseThrow(() -> new NoSuchElementException("Worker not found: " + preferredWorkerId));
        if (evaluateEligibility(preferredWorker, requestedResources).eligible()) {
            return preferredWorker;
        }

        return selectAuto(requestedResources);
    }

    WorkerEligibilityResult evaluateEligibility(Worker worker, ResourceRequest requestedResources) {
        Worker candidate = Objects.requireNonNull(worker, "worker must not be null.");
        ResourceRequest resources = Objects.requireNonNull(
                requestedResources,
                "requestedResources must not be null."
        );

        if (candidate.getApprovalStatus() != WorkerApprovalStatus.APPROVED) {
            return WorkerEligibilityResult.rejected(WorkerRejectionReason.NOT_APPROVED);
        }
        if (candidate.getConnectionStatus() != WorkerConnectionStatus.ONLINE) {
            return WorkerEligibilityResult.rejected(WorkerRejectionReason.NOT_ONLINE);
        }
        if (candidate.getAvailabilityStatus() != WorkerAvailabilityStatus.AVAILABLE) {
            return WorkerEligibilityResult.rejected(WorkerRejectionReason.NOT_AVAILABLE);
        }
        if (assignmentRepository.existsByWorkerAndExecution_StatusIn(candidate, ACTIVE_EXECUTION_STATUSES)) {
            return WorkerEligibilityResult.rejected(WorkerRejectionReason.HAS_ACTIVE_EXECUTION);
        }

        WorkerRejectionReason resourceRejectionReason = resourceRejectionReason(candidate, resources);
        if (resourceRejectionReason != null) {
            return WorkerEligibilityResult.rejected(resourceRejectionReason);
        }

        return WorkerEligibilityResult.accepted();
    }

    private java.util.Optional<Worker> selectBestEligibleWorker(ResourceRequest requestedResources) {
        ResourceRequest resources = Objects.requireNonNull(
                requestedResources,
                "requestedResources must not be null."
        );
        var candidates = workerRepository.findWorkerSelectionCandidates(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE,
                ACTIVE_EXECUTION_STATUSES
        );
        Map<UUID, LocalDateTime> latestAssignedAtByWorkerId = latestAssignedAtByWorkerId(candidates);

        return candidates.stream()
                .filter(worker -> resourceRejectionReason(worker, resources) == null)
                .map(worker -> new WorkerCandidate(
                        worker,
                        memoryHeadroomMb(worker, resources),
                        cpuHeadroom(worker, resources),
                        latestAssignedAtByWorkerId.get(worker.getId())
                ))
                .sorted(CANDIDATE_ORDER)
                .map(WorkerCandidate::worker)
                .findFirst();
    }

    private Map<UUID, LocalDateTime> latestAssignedAtByWorkerId(Collection<Worker> workers) {
        if (workers.isEmpty()) {
            return Map.of();
        }

        Set<UUID> workerIds = workers.stream()
                .map(Worker::getId)
                .collect(Collectors.toSet());
        return assignmentRepository.findLatestAssignedAtByWorkerIds(workerIds).stream()
                .collect(Collectors.toMap(
                        ExecutionAssignmentRepository.LatestWorkerAssignment::getWorkerId,
                        ExecutionAssignmentRepository.LatestWorkerAssignment::getLatestAssignedAt
                ));
    }

    private static WorkerRejectionReason resourceRejectionReason(Worker worker, ResourceRequest requestedResources) {
        if (requestedResources.isGpuRequired()) {
            return WorkerRejectionReason.GPU_REQUIRED_UNSUPPORTED;
        }

        int requiredMemoryMb = requestedResources.getRequiredRamMb();
        if (requiredMemoryMb > 0) {
            Integer sharedRamMb = worker.getSharedRamMb();
            if (sharedRamMb == null || sharedRamMb <= 0 || sharedRamMb < requiredMemoryMb) {
                return WorkerRejectionReason.INSUFFICIENT_MEMORY;
            }
        }

        int requiredCpuCores = requestedResources.getRequiredCpuCores();
        if (requiredCpuCores > 0) {
            Integer cpuCores = worker.getCpuCores();
            if (cpuCores == null || cpuCores <= 0 || cpuCores < requiredCpuCores) {
                return WorkerRejectionReason.INSUFFICIENT_CPU;
            }
        }

        return null;
    }

    private static int memoryHeadroomMb(Worker worker, ResourceRequest requestedResources) {
        int sharedRamMb = worker.getSharedRamMb() == null ? 0 : worker.getSharedRamMb();
        return sharedRamMb - requestedResources.getRequiredRamMb();
    }

    private static int cpuHeadroom(Worker worker, ResourceRequest requestedResources) {
        int cpuCores = worker.getCpuCores() == null ? 0 : worker.getCpuCores();
        return cpuCores - requestedResources.getRequiredCpuCores();
    }

    public static class NoEligibleWorkerException extends RuntimeException {

        public NoEligibleWorkerException() {
            super("No eligible worker available for assignment.");
        }
    }

    record WorkerEligibilityResult(boolean eligible, WorkerRejectionReason rejectionReason) {

        static WorkerEligibilityResult accepted() {
            return new WorkerEligibilityResult(true, null);
        }

        static WorkerEligibilityResult rejected(WorkerRejectionReason reason) {
            return new WorkerEligibilityResult(false, Objects.requireNonNull(reason, "reason must not be null."));
        }
    }

    enum WorkerRejectionReason {
        NOT_APPROVED,
        NOT_ONLINE,
        NOT_AVAILABLE,
        HAS_ACTIVE_EXECUTION,
        INSUFFICIENT_MEMORY,
        INSUFFICIENT_CPU,
        GPU_REQUIRED_UNSUPPORTED
    }

    private record WorkerCandidate(
            Worker worker,
            int memoryHeadroomMb,
            int cpuHeadroom,
            LocalDateTime latestAssignedAt
    ) {
    }
}
