package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
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

    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";

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
    private final WorkerCapabilitiesRepository workerCapabilitiesRepository;

    public Worker selectAuto(WorkerSelectionCriteria selectionCriteria) {
        return selectBestEligibleWorker(selectionCriteria)
                .orElseThrow(NoEligibleWorkerException::new);
    }

    public Worker selectPreferred(UUID preferredWorkerId, WorkerSelectionCriteria selectionCriteria) {
        if (preferredWorkerId == null) {
            throw new IllegalArgumentException("workerId is required for PREFER assignmentMode.");
        }

        Worker preferredWorker = workerRepository.findById(preferredWorkerId)
                .orElseThrow(() -> new NoSuchElementException("Worker not found: " + preferredWorkerId));
        WorkerCapabilities capabilities = workerCapabilitiesRepository.findById(preferredWorker.getId()).orElse(null);
        if (evaluateEligibility(preferredWorker, selectionCriteria, capabilities).eligible()) {
            return preferredWorker;
        }

        return selectAuto(selectionCriteria);
    }

    WorkerEligibilityResult evaluateEligibility(Worker worker,
                                                WorkerSelectionCriteria selectionCriteria,
                                                WorkerCapabilities capabilities) {
        Worker candidate = Objects.requireNonNull(worker, "worker must not be null.");
        WorkerSelectionCriteria criteria = Objects.requireNonNull(
                selectionCriteria,
                "selectionCriteria must not be null."
        );
        ResourceRequest resources = criteria.requestedResources();

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

        WorkerRejectionReason capabilityRejectionReason = capabilityRejectionReason(capabilities, criteria);
        if (capabilityRejectionReason != null) {
            return WorkerEligibilityResult.rejected(capabilityRejectionReason);
        }

        return WorkerEligibilityResult.accepted();
    }

    private java.util.Optional<Worker> selectBestEligibleWorker(WorkerSelectionCriteria selectionCriteria) {
        WorkerSelectionCriteria criteria = Objects.requireNonNull(
                selectionCriteria,
                "selectionCriteria must not be null."
        );
        ResourceRequest resources = criteria.requestedResources();
        var candidates = workerRepository.findWorkerSelectionCandidates(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE,
                ACTIVE_EXECUTION_STATUSES
        );
        Map<UUID, LocalDateTime> latestAssignedAtByWorkerId = latestAssignedAtByWorkerId(candidates);
        Map<UUID, WorkerCapabilities> capabilitiesByWorkerId = capabilitiesByWorkerId(candidates);

        return candidates.stream()
                .filter(worker -> resourceRejectionReason(worker, resources) == null)
                .filter(worker -> capabilityRejectionReason(
                        capabilitiesByWorkerId.get(worker.getId()),
                        criteria
                ) == null)
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

    private Map<UUID, WorkerCapabilities> capabilitiesByWorkerId(Collection<Worker> workers) {
        if (workers.isEmpty()) {
            return Map.of();
        }

        Set<UUID> workerIds = workers.stream()
                .map(Worker::getId)
                .collect(Collectors.toSet());
        return workerCapabilitiesRepository.findAllById(workerIds).stream()
                .collect(Collectors.toMap(WorkerCapabilities::getWorkerId, Function.identity()));
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

    private static WorkerRejectionReason capabilityRejectionReason(WorkerCapabilities capabilities,
                                                                   WorkerSelectionCriteria criteria) {
        if (capabilities == null) {
            return WorkerRejectionReason.MISSING_CAPABILITIES;
        }

        JsonNode executor = matchingExecutor(capabilities.getExecutors(), criteria);
        if (executor == null) {
            return WorkerRejectionReason.EXECUTOR_NOT_SUPPORTED;
        }
        if (!executor.path("enabled").asBoolean(false)) {
            return WorkerRejectionReason.EXECUTOR_DISABLED;
        }

        if (DOCKER_EXECUTOR_ID.equals(criteria.executorId())) {
            return dockerCapabilityRejectionReason(capabilities, criteria);
        }

        return null;
    }

    private static JsonNode matchingExecutor(JsonNode executors, WorkerSelectionCriteria criteria) {
        if (executors == null || !executors.isArray()) {
            return null;
        }

        for (JsonNode executor : executors) {
            if (criteria.executorId().equals(executor.path("executorId").asText(null))
                    && criteria.executorContractVersion() == executor.path("executorContractVersion").asInt(-1)) {
                return executor;
            }
        }

        return null;
    }

    private static WorkerRejectionReason dockerCapabilityRejectionReason(WorkerCapabilities capabilities,
                                                                         WorkerSelectionCriteria criteria) {
        if (isDockerSummaryMissing(capabilities)) {
            return WorkerRejectionReason.DOCKER_CAPABILITY_MISSING;
        }
        if (!Boolean.TRUE.equals(capabilities.getDockerEnabled())) {
            return WorkerRejectionReason.DOCKER_DISABLED;
        }
        if (!containsText(capabilities.getDockerAllowedImages(), criteria.dockerImage())) {
            return WorkerRejectionReason.DOCKER_IMAGE_NOT_ALLOWED;
        }
        Integer maxMemoryMb = capabilities.getDockerMaxMemoryMb();
        if (maxMemoryMb != null && criteria.requestedResources().getRequiredRamMb() > maxMemoryMb) {
            return WorkerRejectionReason.DOCKER_POLICY_MEMORY_EXCEEDED;
        }
        Integer maxCpuCores = capabilities.getDockerMaxCpuCores();
        if (maxCpuCores != null && criteria.requestedResources().getRequiredCpuCores() > maxCpuCores) {
            return WorkerRejectionReason.DOCKER_POLICY_CPU_EXCEEDED;
        }

        return null;
    }

    private static boolean isDockerSummaryMissing(WorkerCapabilities capabilities) {
        return capabilities.getDockerEnabled() == null
                && capabilities.getDockerAllowedImages() == null
                && capabilities.getDockerMaxMemoryMb() == null
                && capabilities.getDockerMaxCpuCores() == null
                && capabilities.getDockerGpuAllowed() == null;
    }

    private static boolean containsText(JsonNode values, String expectedValue) {
        if (expectedValue == null || values == null || !values.isArray()) {
            return false;
        }

        for (JsonNode value : values) {
            if (value.isTextual() && expectedValue.equals(value.asText())) {
                return true;
            }
        }

        return false;
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
        GPU_REQUIRED_UNSUPPORTED,
        MISSING_CAPABILITIES,
        EXECUTOR_NOT_SUPPORTED,
        EXECUTOR_DISABLED,
        DOCKER_CAPABILITY_MISSING,
        DOCKER_DISABLED,
        DOCKER_IMAGE_NOT_ALLOWED,
        DOCKER_POLICY_MEMORY_EXCEEDED,
        DOCKER_POLICY_CPU_EXCEEDED
    }

    private record WorkerCandidate(
            Worker worker,
            int memoryHeadroomMb,
            int cpuHeadroom,
            LocalDateTime latestAssignedAt
    ) {
    }
}
