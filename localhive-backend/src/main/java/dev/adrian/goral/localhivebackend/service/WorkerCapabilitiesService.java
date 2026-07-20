package dev.adrian.goral.localhivebackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import dev.adrian.goral.localhivebackend.dto.AdminWorkerDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.WorkerCapabilitiesDto;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkerCapabilitiesService {

    private static final int MAX_EXECUTORS = 50;
    private static final int MAX_ALLOWED_IMAGES = 100;
    private static final int MAX_TEXT_LENGTH = 255;

    private final WorkerCapabilitiesRepository workerCapabilitiesRepository;

    @Transactional
    public WorkerCapabilities replaceCapabilities(Worker worker, WorkerCapabilitiesDto capabilities, LocalDateTime reportedAt) {
        ValidatedCapabilities validated = validate(capabilities);

        WorkerCapabilities snapshot = workerCapabilitiesRepository.findById(worker.getId())
                .orElseGet(() -> WorkerCapabilities.create(worker));
        snapshot.replaceWith(
                reportedAt,
                validated.executors(),
                validated.dockerEnabled(),
                validated.dockerAllowedImages(),
                validated.dockerMaxMemoryMb(),
                validated.dockerMaxCpuCores(),
                validated.dockerGpuAllowed()
        );

        return workerCapabilitiesRepository.save(snapshot);
    }

    @Transactional(readOnly = true)
    public Optional<AdminWorkerDetailResponseDto.CapabilitiesDto> findCapabilities(UUID workerId) {
        return workerCapabilitiesRepository.findById(workerId)
                .map(this::toAdminDto);
    }

    private ValidatedCapabilities validate(WorkerCapabilitiesDto capabilities) {
        if (capabilities == null) {
            throw new IllegalStateException("capabilities are required.");
        }

        List<WorkerCapabilitiesDto.ExecutorCapabilityDto> executors = capabilities.executors();
        if (executors == null) {
            throw new IllegalStateException("capabilities.executors is required.");
        }
        if (executors.size() > MAX_EXECUTORS) {
            throw new IllegalStateException("capabilities.executors cannot contain more than 50 entries.");
        }

        ArrayNode executorsJson = JsonNodeFactory.instance.arrayNode();
        for (WorkerCapabilitiesDto.ExecutorCapabilityDto executor : executors) {
            if (executor == null) {
                throw new IllegalStateException("capabilities.executors cannot contain null entries.");
            }

            String executorId = requireText(executor.executorId(), "capabilities.executors.executorId");
            int executorContractVersion = requirePositive(
                    executor.executorContractVersion(),
                    "capabilities.executors.executorContractVersion"
            );
            boolean enabled = executor.enabled() != null && executor.enabled();

            executorsJson.addObject()
                    .put("executorId", executorId)
                    .put("executorContractVersion", executorContractVersion)
                    .put("enabled", enabled);
        }

        WorkerCapabilitiesDto.DockerCapabilityDto docker = capabilities.docker();
        if (docker == null) {
            return new ValidatedCapabilities(executorsJson, null, null, null, null, null);
        }

        return new ValidatedCapabilities(
                executorsJson,
                docker.enabled() != null && docker.enabled(),
                validateAllowedImages(docker.allowedImages()),
                requireNonNegative(docker.maxMemoryMb(), "capabilities.docker.maxMemoryMb"),
                requireNonNegative(docker.maxCpuCores(), "capabilities.docker.maxCpuCores"),
                docker.gpuAllowed() != null && docker.gpuAllowed()
        );
    }

    private ArrayNode validateAllowedImages(List<String> allowedImages) {
        if (allowedImages == null) {
            return null;
        }
        if (allowedImages.size() > MAX_ALLOWED_IMAGES) {
            throw new IllegalStateException("capabilities.docker.allowedImages cannot contain more than 100 entries.");
        }

        ArrayNode imagesJson = JsonNodeFactory.instance.arrayNode();
        for (String allowedImage : allowedImages) {
            imagesJson.add(requireText(allowedImage, "capabilities.docker.allowedImages"));
        }
        return imagesJson;
    }

    private AdminWorkerDetailResponseDto.CapabilitiesDto toAdminDto(WorkerCapabilities capabilities) {
        return new AdminWorkerDetailResponseDto.CapabilitiesDto(
                capabilities.getReportedAt(),
                toExecutorDtos(capabilities.getExecutors()),
                toDockerDto(capabilities)
        );
    }

    private List<AdminWorkerDetailResponseDto.ExecutorCapabilityDto> toExecutorDtos(JsonNode executors) {
        if (executors == null || !executors.isArray()) {
            return List.of();
        }

        List<AdminWorkerDetailResponseDto.ExecutorCapabilityDto> items = new ArrayList<>();
        for (JsonNode executor : executors) {
            items.add(new AdminWorkerDetailResponseDto.ExecutorCapabilityDto(
                    executor.path("executorId").asText(),
                    executor.path("executorContractVersion").asInt(),
                    executor.path("enabled").asBoolean()
            ));
        }
        return items;
    }

    private AdminWorkerDetailResponseDto.DockerCapabilityDto toDockerDto(WorkerCapabilities capabilities) {
        if (capabilities.getDockerEnabled() == null
                && capabilities.getDockerAllowedImages() == null
                && capabilities.getDockerMaxMemoryMb() == null
                && capabilities.getDockerMaxCpuCores() == null
                && capabilities.getDockerGpuAllowed() == null) {
            return null;
        }

        return new AdminWorkerDetailResponseDto.DockerCapabilityDto(
                capabilities.getDockerEnabled(),
                toStringList(capabilities.getDockerAllowedImages()),
                capabilities.getDockerMaxMemoryMb(),
                capabilities.getDockerMaxCpuCores(),
                capabilities.getDockerGpuAllowed()
        );
    }

    private List<String> toStringList(JsonNode items) {
        if (items == null || !items.isArray()) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : items) {
            values.add(item.asText());
        }
        return values;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " is required.");
        }

        String normalized = value.trim();
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalStateException(fieldName + " must be at most 255 characters.");
        }
        return normalized;
    }

    private int requirePositive(Integer value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException(fieldName + " is required.");
        }
        if (value < 1) {
            throw new IllegalStateException(fieldName + " must be positive.");
        }
        return value;
    }

    private Integer requireNonNegative(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw new IllegalStateException(fieldName + " cannot be negative.");
        }
        return value;
    }

    private record ValidatedCapabilities(
            ArrayNode executors,
            Boolean dockerEnabled,
            ArrayNode dockerAllowedImages,
            Integer dockerMaxMemoryMb,
            Integer dockerMaxCpuCores,
            Boolean dockerGpuAllowed
    ) {
    }
}
