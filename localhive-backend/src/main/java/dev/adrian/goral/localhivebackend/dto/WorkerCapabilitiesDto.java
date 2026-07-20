package dev.adrian.goral.localhivebackend.dto;

import java.util.List;

public record WorkerCapabilitiesDto(
        List<ExecutorCapabilityDto> executors,
        DockerCapabilityDto docker
) {
    public WorkerCapabilitiesDto {
        executors = executors == null ? null : List.copyOf(executors);
    }

    public record ExecutorCapabilityDto(
            String executorId,
            Integer executorContractVersion,
            Boolean enabled
    ) {
    }

    public record DockerCapabilityDto(
            Boolean enabled,
            List<String> allowedImages,
            Integer maxMemoryMb,
            Integer maxCpuCores,
            Boolean gpuAllowed
    ) {
        public DockerCapabilityDto {
            allowedImages = allowedImages == null ? null : List.copyOf(allowedImages);
        }
    }
}
