package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkInstanceManagementService {

    private final WorkInstanceRepository instanceRepository;
    private final WorkDefinitionVersionRepository versionRepository;
    private final ConfigurationResolver configurationResolver;
    private final ResourceRequestResolver resourceRequestResolver;

    @Transactional
    public WorkInstance createInstance(CreateWorkInstanceCommand command) {
        Objects.requireNonNull(command, "command must not be null.");
        WorkDefinitionVersion definitionVersion = findVersion(command.definitionVersionId());
        WorkInstance instance = WorkInstance.create(
                definitionVersion,
                command.displayName(),
                command.configurationOverrides(),
                command.resourceOverrides(),
                LocalDateTime.now()
        );

        return instanceRepository.save(instance);
    }

    @Transactional
    public WorkInstance renameInstance(UUID instanceId, String displayName) {
        WorkInstance instance = findInstance(instanceId);
        instance.rename(displayName, LocalDateTime.now());
        return instance;
    }

    @Transactional
    public WorkInstance enableInstance(UUID instanceId) {
        WorkInstance instance = findInstance(instanceId);
        instance.enable(LocalDateTime.now());
        return instance;
    }

    @Transactional
    public WorkInstance disableInstance(UUID instanceId) {
        WorkInstance instance = findInstance(instanceId);
        instance.disable(LocalDateTime.now());
        return instance;
    }

    @Transactional
    public WorkInstance updateConfigurationOverrides(UUID instanceId, JsonNode configurationOverrides) {
        WorkInstance instance = findInstance(instanceId);
        instance.updateConfigurationOverrides(configurationOverrides, LocalDateTime.now());
        return instance;
    }

    @Transactional
    public WorkInstance updateResourceOverrides(UUID instanceId, ResourceRequestOverrides resourceOverrides) {
        WorkInstance instance = findInstance(instanceId);
        instance.updateResourceOverrides(resourceOverrides, LocalDateTime.now());
        return instance;
    }

    @Transactional
    public WorkInstance upgradeInstanceDefinitionVersion(UUID instanceId, UUID definitionVersionId) {
        WorkInstance instance = findInstance(instanceId);
        WorkDefinitionVersion targetVersion = findVersion(definitionVersionId);
        instance.upgradeDefinitionVersion(targetVersion, LocalDateTime.now());
        return instance;
    }

    @Transactional(readOnly = true)
    public JsonNode resolveConfiguration(UUID instanceId) {
        WorkInstance instance = findInstance(instanceId);
        return configurationResolver.resolve(
                instance.getDefinitionVersion().getExecutorConfiguration(),
                instance.getConfigurationOverrides()
        );
    }

    @Transactional(readOnly = true)
    public ResourceRequest resolveResourceRequest(UUID instanceId) {
        WorkInstance instance = findInstance(instanceId);
        return resourceRequestResolver.resolve(
                instance.getDefinitionVersion().getDefaultResourceRequest(),
                instance.getResourceOverrides()
        );
    }

    private WorkInstance findInstance(UUID instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId must not be null.");
        }

        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Work instance not found: " + instanceId));
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
}
