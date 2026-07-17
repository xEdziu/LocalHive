package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionApprovalStatus;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkExecutionCreationService {

    private final WorkExecutionRepository executionRepository;
    private final WorkDefinitionVersionRepository versionRepository;
    private final WorkInstanceRepository instanceRepository;
    private final ConfigurationResolver configurationResolver;
    private final ResourceRequestResolver resourceRequestResolver;

    @Transactional
    public WorkExecution createOneOffExecution(CreateOneOffExecutionCommand command) {
        Objects.requireNonNull(command, "command must not be null.");
        WorkDefinitionVersion definitionVersion = findVersion(command.definitionVersionId());
        requireApproved(definitionVersion);

        JsonNode resolvedConfiguration = configurationResolver.resolve(
                definitionVersion.getExecutorConfiguration(),
                defaultConfigurationOverrides(command.configurationOverrides())
        );
        ResourceRequest resolvedResourceRequest = resourceRequestResolver.resolve(
                definitionVersion.getDefaultResourceRequest(),
                defaultResourceOverrides(command.resourceOverrides())
        );

        WorkExecution execution = WorkExecution.createQueued(
                definitionVersion,
                null,
                resolvedConfiguration,
                resolvedResourceRequest,
                LocalDateTime.now()
        );
        return executionRepository.save(execution);
    }

    @Transactional
    public WorkExecution createInstanceExecution(CreateInstanceExecutionCommand command) {
        Objects.requireNonNull(command, "command must not be null.");
        WorkInstance instance = findInstance(command.instanceId());
        if (!instance.isEnabled()) {
            throw new IllegalStateException("Work instance is disabled.");
        }

        WorkDefinitionVersion definitionVersion = instance.getDefinitionVersion();
        requireApproved(definitionVersion);

        JsonNode resolvedConfiguration = configurationResolver.resolve(
                definitionVersion.getExecutorConfiguration(),
                instance.getConfigurationOverrides()
        );
        ResourceRequest resolvedResourceRequest = resourceRequestResolver.resolve(
                definitionVersion.getDefaultResourceRequest(),
                instance.getResourceOverrides()
        );

        WorkExecution execution = WorkExecution.createQueued(
                definitionVersion,
                instance,
                resolvedConfiguration,
                resolvedResourceRequest,
                LocalDateTime.now()
        );
        return executionRepository.save(execution);
    }

    private WorkDefinitionVersion findVersion(UUID definitionVersionId) {
        if (definitionVersionId == null) {
            throw new IllegalArgumentException("definitionVersionId must not be null.");
        }

        return versionRepository.findById(definitionVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Work definition version not found: " + definitionVersionId
                ));
    }

    private WorkInstance findInstance(UUID instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId must not be null.");
        }

        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Work instance not found: " + instanceId));
    }

    private static void requireApproved(WorkDefinitionVersion definitionVersion) {
        if (definitionVersion.getApprovalStatus() != DefinitionApprovalStatus.APPROVED) {
            throw new IllegalStateException(
                    "Work definition version must be APPROVED to create execution. Current status: "
                            + definitionVersion.getApprovalStatus()
            );
        }
    }

    private static JsonNode defaultConfigurationOverrides(JsonNode configurationOverrides) {
        return configurationOverrides == null
                ? JsonNodeFactory.instance.objectNode()
                : configurationOverrides;
    }

    private static ResourceRequestOverrides defaultResourceOverrides(ResourceRequestOverrides resourceOverrides) {
        return resourceOverrides == null ? ResourceRequestOverrides.empty() : resourceOverrides;
    }
}
