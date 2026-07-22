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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
        return WorkerEligibilityResult.from(evaluateRejectionReasons(
                worker,
                selectionCriteria,
                capabilities,
                true
        ));
    }

    List<WorkerSelectionCandidate> evaluateDiagnosticCandidates(Collection<Worker> workers,
                                                                WorkerSelectionCriteria selectionCriteria) {
        if (workers == null || workers.isEmpty()) {
            return List.of();
        }

        WorkerSelectionCriteria criteria = Objects.requireNonNull(
                selectionCriteria,
                "selectionCriteria must not be null."
        );
        Map<UUID, LocalDateTime> latestAssignedAtByWorkerId = latestAssignedAtByWorkerId(workers);
        Map<UUID, WorkerCapabilities> capabilitiesByWorkerId = capabilitiesByWorkerId(workers);

        return workers.stream()
                .map(worker -> diagnosticCandidate(
                        worker,
                        criteria,
                        capabilitiesByWorkerId.get(worker.getId()),
                        latestAssignedAtByWorkerId.get(worker.getId())
                ))
                .sorted(WorkerSelectionService::compareDiagnosticCandidates)
                .toList();
    }

    WorkerSelectionCandidate diagnosticCandidate(Worker worker,
                                                 WorkerSelectionCriteria selectionCriteria,
                                                 WorkerCapabilities capabilities,
                                                 LocalDateTime latestAssignedAt) {
        Worker candidate = Objects.requireNonNull(worker, "worker must not be null.");
        WorkerSelectionCriteria criteria = Objects.requireNonNull(
                selectionCriteria,
                "selectionCriteria must not be null."
        );
        ResourceRequest resources = criteria.requestedResources();
        List<WorkerRejectionReason> rejectionReasons = evaluateRejectionReasons(
                candidate,
                criteria,
                capabilities,
                true
        );

        return new WorkerSelectionCandidate(
                candidate,
                capabilities,
                WorkerEligibilityResult.from(rejectionReasons),
                rejectionReasons,
                capabilityDiagnostics(capabilities, criteria),
                memoryHeadroomMb(candidate, resources),
                cpuHeadroom(candidate, resources),
                latestAssignedAt
        );
    }

    private List<WorkerRejectionReason> evaluateRejectionReasons(Worker worker,
                                                                 WorkerSelectionCriteria selectionCriteria,
                                                                 WorkerCapabilities capabilities,
                                                                 boolean applyCapabilityReasons) {
        Worker candidate = Objects.requireNonNull(worker, "worker must not be null.");
        WorkerSelectionCriteria criteria = Objects.requireNonNull(
                selectionCriteria,
                "selectionCriteria must not be null."
        );
        ResourceRequest resources = criteria.requestedResources();
        List<WorkerRejectionReason> reasons = new ArrayList<>();

        if (candidate.getApprovalStatus() != WorkerApprovalStatus.APPROVED) {
            reasons.add(WorkerRejectionReason.NOT_APPROVED);
        }
        if (candidate.getConnectionStatus() != WorkerConnectionStatus.ONLINE) {
            reasons.add(WorkerRejectionReason.NOT_ONLINE);
        }
        if (candidate.getAvailabilityStatus() != WorkerAvailabilityStatus.AVAILABLE) {
            reasons.add(WorkerRejectionReason.NOT_AVAILABLE);
        }
        if (assignmentRepository.existsByWorkerAndExecution_StatusIn(candidate, ACTIVE_EXECUTION_STATUSES)) {
            reasons.add(WorkerRejectionReason.HAS_ACTIVE_EXECUTION);
        }

        reasons.addAll(resourceRejectionReasons(candidate, resources));

        if (applyCapabilityReasons) {
            reasons.addAll(capabilityRejectionReasons(capabilities, criteria));
        }

        return List.copyOf(reasons);
    }

    private Optional<Worker> selectBestEligibleWorker(WorkerSelectionCriteria selectionCriteria) {
        WorkerSelectionCriteria criteria = Objects.requireNonNull(
                selectionCriteria,
                "selectionCriteria must not be null."
        );
        var candidates = workerRepository.findWorkerSelectionCandidates(
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE,
                ACTIVE_EXECUTION_STATUSES
        );

        return evaluateDiagnosticCandidates(candidates, criteria).stream()
                .filter(candidate -> candidate.eligibilityResult().eligible())
                .map(WorkerSelectionCandidate::worker)
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

    private static List<WorkerRejectionReason> resourceRejectionReasons(Worker worker,
                                                                        ResourceRequest requestedResources) {
        List<WorkerRejectionReason> reasons = new ArrayList<>();

        if (requestedResources.isGpuRequired()) {
            reasons.add(WorkerRejectionReason.GPU_REQUIRED_UNSUPPORTED);
        }

        int requiredMemoryMb = requestedResources.getRequiredRamMb();
        if (requiredMemoryMb > 0) {
            Integer sharedRamMb = worker.getSharedRamMb();
            if (sharedRamMb == null || sharedRamMb <= 0 || sharedRamMb < requiredMemoryMb) {
                reasons.add(WorkerRejectionReason.INSUFFICIENT_MEMORY);
            }
        }

        int requiredCpuCores = requestedResources.getRequiredCpuCores();
        if (requiredCpuCores > 0) {
            Integer cpuCores = worker.getCpuCores();
            if (cpuCores == null || cpuCores <= 0 || cpuCores < requiredCpuCores) {
                reasons.add(WorkerRejectionReason.INSUFFICIENT_CPU);
            }
        }

        return List.copyOf(reasons);
    }

    private static List<WorkerRejectionReason> capabilityRejectionReasons(WorkerCapabilities capabilities,
                                                                          WorkerSelectionCriteria criteria) {
        List<WorkerRejectionReason> reasons = new ArrayList<>();
        CapabilityDiagnostics diagnostics = capabilityDiagnostics(capabilities, criteria);

        if (capabilities == null) {
            return List.of(WorkerRejectionReason.MISSING_CAPABILITIES);
        }

        if (!diagnostics.executorMatched()) {
            return List.of(WorkerRejectionReason.EXECUTOR_NOT_SUPPORTED);
        }
        if (!diagnostics.executorEnabled()) {
            return List.of(WorkerRejectionReason.EXECUTOR_DISABLED);
        }

        if (DOCKER_EXECUTOR_ID.equals(criteria.executorId())) {
            if (!diagnostics.dockerReported()) {
                return List.of(WorkerRejectionReason.DOCKER_CAPABILITY_MISSING);
            }
            if (!diagnostics.dockerEnabled()) {
                reasons.add(WorkerRejectionReason.DOCKER_DISABLED);
            }
            if (!diagnostics.imageAllowed()) {
                reasons.add(WorkerRejectionReason.DOCKER_IMAGE_NOT_ALLOWED);
            }
            if (!diagnostics.policyMemoryFits()) {
                reasons.add(WorkerRejectionReason.DOCKER_POLICY_MEMORY_EXCEEDED);
            }
            if (!diagnostics.policyCpuFits()) {
                reasons.add(WorkerRejectionReason.DOCKER_POLICY_CPU_EXCEEDED);
            }
        }

        return List.copyOf(reasons);
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

    private static CapabilityDiagnostics capabilityDiagnostics(WorkerCapabilities capabilities,
                                                              WorkerSelectionCriteria criteria) {
        if (capabilities == null) {
            return CapabilityDiagnostics.empty();
        }

        JsonNode executor = matchingExecutor(capabilities.getExecutors(), criteria);
        boolean executorMatched = executor != null;
        boolean executorEnabled = executorMatched && executor.path("enabled").asBoolean(false);
        boolean dockerReported = !isDockerSummaryMissing(capabilities);
        boolean dockerEnabled = Boolean.TRUE.equals(capabilities.getDockerEnabled());
        boolean imageAllowed = containsText(capabilities.getDockerAllowedImages(), criteria.dockerImage());

        Integer maxMemoryMb = capabilities.getDockerMaxMemoryMb();
        boolean policyMemoryFits = dockerReported
                && (maxMemoryMb == null || criteria.requestedResources().getRequiredRamMb() <= maxMemoryMb);
        Integer maxCpuCores = capabilities.getDockerMaxCpuCores();
        boolean policyCpuFits = dockerReported
                && (maxCpuCores == null || criteria.requestedResources().getRequiredCpuCores() <= maxCpuCores);

        return new CapabilityDiagnostics(
                true,
                executorMatched,
                executorEnabled,
                dockerReported,
                dockerEnabled,
                imageAllowed,
                policyMemoryFits,
                policyCpuFits
        );
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

    private static int compareDiagnosticCandidates(WorkerSelectionCandidate first,
                                                   WorkerSelectionCandidate second) {
        if (first.eligibilityResult().eligible() && second.eligibilityResult().eligible()) {
            return CANDIDATE_ORDER.compare(first.toWorkerCandidate(), second.toWorkerCandidate());
        }
        if (first.eligibilityResult().eligible() != second.eligibilityResult().eligible()) {
            return Boolean.compare(second.eligibilityResult().eligible(), first.eligibilityResult().eligible());
        }

        int hostnameResult = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                .compare(first.worker().getHostname(), second.worker().getHostname());
        if (hostnameResult != 0) {
            return hostnameResult;
        }

        return first.worker().getId().compareTo(second.worker().getId());
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

        static WorkerEligibilityResult from(List<WorkerRejectionReason> reasons) {
            if (reasons == null || reasons.isEmpty()) {
                return accepted();
            }

            return rejected(reasons.get(0));
        }
    }

    enum WorkerRejectionReason {
        NOT_APPROVED("WORKER_NOT_APPROVED"),
        NOT_ONLINE("WORKER_OFFLINE"),
        NOT_AVAILABLE("WORKER_NOT_AVAILABLE"),
        HAS_ACTIVE_EXECUTION("WORKER_HAS_ACTIVE_EXECUTION"),
        INSUFFICIENT_MEMORY("WORKER_MEMORY_TOO_LOW"),
        INSUFFICIENT_CPU("WORKER_CPU_TOO_LOW"),
        GPU_REQUIRED_UNSUPPORTED("GPU_UNSUPPORTED"),
        MISSING_CAPABILITIES("MISSING_CAPABILITIES"),
        EXECUTOR_NOT_SUPPORTED("EXECUTOR_NOT_SUPPORTED"),
        EXECUTOR_DISABLED("EXECUTOR_DISABLED"),
        DOCKER_CAPABILITY_MISSING("DOCKER_CAPABILITY_MISSING"),
        DOCKER_DISABLED("DOCKER_DISABLED"),
        DOCKER_IMAGE_NOT_ALLOWED("DOCKER_IMAGE_NOT_ALLOWED"),
        DOCKER_POLICY_MEMORY_EXCEEDED("DOCKER_POLICY_MEMORY_EXCEEDED"),
        DOCKER_POLICY_CPU_EXCEEDED("DOCKER_POLICY_CPU_EXCEEDED");

        private final String code;

        WorkerRejectionReason(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record WorkerSelectionCandidate(
            Worker worker,
            WorkerCapabilities capabilities,
            WorkerEligibilityResult eligibilityResult,
            List<WorkerRejectionReason> rejectionReasons,
            CapabilityDiagnostics capabilityDiagnostics,
            int memoryHeadroomMb,
            int cpuHeadroom,
            LocalDateTime latestAssignedAt
    ) {

        WorkerSelectionCandidate {
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        }

        private WorkerCandidate toWorkerCandidate() {
            return new WorkerCandidate(worker, memoryHeadroomMb, cpuHeadroom, latestAssignedAt);
        }
    }

    record CapabilityDiagnostics(
            boolean reported,
            boolean executorMatched,
            boolean executorEnabled,
            boolean dockerReported,
            boolean dockerEnabled,
            boolean imageAllowed,
            boolean policyMemoryFits,
            boolean policyCpuFits
    ) {

        static CapabilityDiagnostics empty() {
            return new CapabilityDiagnostics(false, false, false, false, false, false, false, false);
        }
    }

    private record WorkerCandidate(
            Worker worker,
            int memoryHeadroomMb,
            int cpuHeadroom,
            LocalDateTime latestAssignedAt
    ) {
    }
}
