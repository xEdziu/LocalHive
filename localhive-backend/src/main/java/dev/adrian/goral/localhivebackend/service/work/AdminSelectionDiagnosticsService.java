package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecutionDisplayName;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.dto.AdminCreateExecutionRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminSelectionDiagnosticsResponseDto;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminSelectionDiagnosticsService {

    private final AdminExecutionCreationService creationService;
    private final WorkerSelectionService workerSelectionService;
    private final WorkerRepository workerRepository;

    @Transactional(readOnly = true)
    public AdminSelectionDiagnosticsResponseDto diagnose(AdminCreateExecutionRequestDto request) {
        AdminExecutionRequestPlan plan = creationService.prepareExecutionRequest(request);
        WorkExecutionDisplayName.validateExplicit(request.displayName());

        return switch (plan.assignmentMode()) {
            case REQUIRE -> requireDiagnostics(plan);
            case AUTO -> automaticDiagnostics(plan);
            case PREFER -> preferredDiagnostics(plan);
        };
    }

    private AdminSelectionDiagnosticsResponseDto requireDiagnostics(AdminExecutionRequestPlan plan) {
        Worker worker = findWorker(plan.requestedWorkerId());
        requireApprovedWorker(worker);

        WorkerSelectionService.WorkerSelectionCandidate candidate = workerSelectionService
                .evaluateDiagnosticCandidates(List.of(worker), plan.selectionCriteria())
                .get(0);

        return response(
                plan,
                1,
                worker.getId(),
                List.of(toWorkerDto(candidate, true, true, List.of()))
        );
    }

    private AdminSelectionDiagnosticsResponseDto automaticDiagnostics(AdminExecutionRequestPlan plan) {
        List<WorkerSelectionService.WorkerSelectionCandidate> candidates = workerSelectionService
                .evaluateDiagnosticCandidates(workerRepository.findAll(), plan.selectionCriteria());
        UUID selectedWorkerId = candidates.stream()
                .filter(candidate -> candidate.eligibilityResult().eligible())
                .map(candidate -> candidate.worker().getId())
                .findFirst()
                .orElse(null);

        return response(
                plan,
                eligibleWorkerCount(candidates),
                selectedWorkerId,
                candidates.stream()
                        .map(candidate -> toWorkerDto(
                                candidate,
                                candidate.eligibilityResult().eligible(),
                                candidate.worker().getId().equals(selectedWorkerId),
                                reasonCodes(candidate)
                        ))
                        .toList()
        );
    }

    private AdminSelectionDiagnosticsResponseDto preferredDiagnostics(AdminExecutionRequestPlan plan) {
        Worker preferredWorker = findWorker(plan.requestedWorkerId());
        List<WorkerSelectionService.WorkerSelectionCandidate> candidates = workerSelectionService
                .evaluateDiagnosticCandidates(workerRepository.findAll(), plan.selectionCriteria());
        WorkerSelectionService.WorkerSelectionCandidate preferredCandidate = candidates.stream()
                .filter(candidate -> candidate.worker().getId().equals(preferredWorker.getId()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Worker not found: " + preferredWorker.getId()));

        UUID selectedWorkerId = preferredCandidate.eligibilityResult().eligible()
                ? preferredWorker.getId()
                : candidates.stream()
                        .filter(candidate -> candidate.eligibilityResult().eligible())
                        .map(candidate -> candidate.worker().getId())
                        .findFirst()
                        .orElse(null);

        return response(
                plan,
                eligibleWorkerCount(candidates),
                selectedWorkerId,
                candidates.stream()
                        .map(candidate -> toWorkerDto(
                                candidate,
                                candidate.eligibilityResult().eligible(),
                                candidate.worker().getId().equals(selectedWorkerId),
                                reasonCodes(candidate)
                        ))
                        .toList()
        );
    }

    private Worker findWorker(UUID workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new NoSuchElementException("Worker not found: " + workerId));
    }

    private static void requireApprovedWorker(Worker worker) {
        if (worker.getApprovalStatus() != WorkerApprovalStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "Worker must be APPROVED to receive admin execution assignment."
            );
        }
    }

    private static int eligibleWorkerCount(List<WorkerSelectionService.WorkerSelectionCandidate> candidates) {
        return (int) candidates.stream()
                .filter(candidate -> candidate.eligibilityResult().eligible())
                .count();
    }

    private static AdminSelectionDiagnosticsResponseDto response(
            AdminExecutionRequestPlan plan,
            int eligibleWorkerCount,
            UUID selectedWorkerId,
            List<AdminSelectionDiagnosticsResponseDto.WorkerDiagnosticsDto> workers
    ) {
        WorkDefinitionVersion version = plan.definitionVersion();
        return new AdminSelectionDiagnosticsResponseDto(
                version.getId(),
                version.getDefinition().getLogicalIdentifier(),
                version.getVersionNumber(),
                version.getExecutorId(),
                version.getExecutorContractVersion(),
                plan.assignmentMode().name(),
                eligibleWorkerCount,
                selectedWorkerId,
                workers
        );
    }

    private static AdminSelectionDiagnosticsResponseDto.WorkerDiagnosticsDto toWorkerDto(
            WorkerSelectionService.WorkerSelectionCandidate candidate,
            boolean eligible,
            boolean selected,
            List<String> rejectionReasons
    ) {
        Worker worker = candidate.worker();
        WorkerSelectionService.CapabilityDiagnostics capabilities = candidate.capabilityDiagnostics();
        return new AdminSelectionDiagnosticsResponseDto.WorkerDiagnosticsDto(
                worker.getId(),
                worker.getHostname(),
                new AdminSelectionDiagnosticsResponseDto.StatusDto(
                        worker.getApprovalStatus().name(),
                        worker.getConnectionStatus().name(),
                        worker.getAvailabilityStatus().name()
                ),
                new AdminSelectionDiagnosticsResponseDto.ResourcesDto(
                        worker.getSharedRamMb(),
                        worker.getCpuCores()
                ),
                new AdminSelectionDiagnosticsResponseDto.CapabilitiesDto(
                        capabilities.reported(),
                        capabilities.executorMatched(),
                        capabilities.dockerReported(),
                        capabilities.dockerEnabled(),
                        capabilities.imageAllowed(),
                        capabilities.policyMemoryFits(),
                        capabilities.policyCpuFits()
                ),
                eligible,
                selected,
                rejectionReasons
        );
    }

    private static List<String> reasonCodes(WorkerSelectionService.WorkerSelectionCandidate candidate) {
        return candidate.rejectionReasons().stream()
                .map(WorkerSelectionService.WorkerRejectionReason::code)
                .toList();
    }
}
