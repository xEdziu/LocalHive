package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;

import java.util.Objects;

record WorkerSelectionCriteria(
        String executorId,
        int executorContractVersion,
        ResourceRequest requestedResources,
        String dockerImage
) {

    WorkerSelectionCriteria {
        executorId = Objects.requireNonNull(executorId, "executorId must not be null.").trim();
        if (executorId.isBlank()) {
            throw new IllegalArgumentException("executorId must not be blank.");
        }
        if (executorContractVersion < 1) {
            throw new IllegalArgumentException("executorContractVersion must be positive.");
        }
        requestedResources = Objects.requireNonNull(requestedResources, "requestedResources must not be null.");
        dockerImage = dockerImage == null ? null : dockerImage.trim();
    }
}
