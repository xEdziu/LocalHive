package dev.adrian.goral.localhivebackend.dto;

import java.util.List;
import java.util.UUID;

public record AdminSelectionDiagnosticsResponseDto(
        UUID workDefinitionVersionId,
        String logicalId,
        int version,
        String executorId,
        int executorContractVersion,
        String assignmentMode,
        int eligibleWorkerCount,
        UUID selectedWorkerId,
        List<WorkerDiagnosticsDto> workers
) {

    public AdminSelectionDiagnosticsResponseDto {
        workers = workers == null ? List.of() : List.copyOf(workers);
    }

    public record WorkerDiagnosticsDto(
            UUID workerId,
            String hostname,
            StatusDto status,
            ResourcesDto resources,
            CapabilitiesDto capabilities,
            boolean eligible,
            boolean selected,
            List<String> rejectionReasons
    ) {

        public WorkerDiagnosticsDto {
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        }
    }

    public record StatusDto(
            String approval,
            String connection,
            String availability
    ) {
    }

    public record ResourcesDto(
            Integer sharedRamMb,
            Integer cpuCores
    ) {
    }

    public record CapabilitiesDto(
            boolean reported,
            boolean executorMatched,
            boolean dockerReported,
            boolean dockerEnabled,
            boolean imageAllowed,
            boolean policyMemoryFits,
            boolean policyCpuFits
    ) {
    }
}
