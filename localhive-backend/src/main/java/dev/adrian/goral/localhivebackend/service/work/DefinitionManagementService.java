package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionSourceType;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.exception.DuplicateResourceException;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefinitionManagementService {

    private final WorkDefinitionRepository definitionRepository;
    private final WorkDefinitionVersionRepository versionRepository;
    private final DefinitionContentChecksumService checksumService;

    @Transactional
    public WorkDefinitionVersion createLocalDefinition(DefinitionContentCommand command) {
        DefinitionContent content = validateContent(command);
        ensureLogicalIdentifierIsAvailable(content.logicalIdentifier());

        LocalDateTime now = LocalDateTime.now();
        WorkDefinition definition = WorkDefinition.createLocal(content.logicalIdentifier(), content.workType(), now);
        definitionRepository.save(definition);

        WorkDefinitionVersion version = createLocalVersion(definition, 1, content, now);
        return versionRepository.save(version);
    }

    @Transactional
    public WorkDefinitionVersion createImportedDefinition(DefinitionContentCommand command, String originalDefinitionId) {
        DefinitionContent content = validateContent(command);
        ensureLogicalIdentifierIsAvailable(content.logicalIdentifier());

        LocalDateTime now = LocalDateTime.now();
        WorkDefinition definition = WorkDefinition.createImported(
                content.logicalIdentifier(),
                content.workType(),
                originalDefinitionId,
                now
        );
        definitionRepository.save(definition);

        WorkDefinitionVersion version = createImportedVersion(definition, 1, content, now);
        return versionRepository.save(version);
    }

    @Transactional
    public WorkDefinitionVersion addLocalVersion(DefinitionContentCommand command) {
        DefinitionContent content = validateContent(command);
        WorkDefinition definition = findDefinitionForVersionCreation(content, DefinitionSourceType.LOCAL);

        int nextVersionNumber = nextVersionNumber(definition);
        WorkDefinitionVersion version = createLocalVersion(definition, nextVersionNumber, content, LocalDateTime.now());
        return versionRepository.save(version);
    }

    @Transactional
    public WorkDefinitionVersion addImportedVersion(DefinitionContentCommand command) {
        DefinitionContent content = validateContent(command);
        WorkDefinition definition = findDefinitionForVersionCreation(content, DefinitionSourceType.IMPORTED);

        int nextVersionNumber = nextVersionNumber(definition);
        WorkDefinitionVersion version = createImportedVersion(definition, nextVersionNumber, content, LocalDateTime.now());
        return versionRepository.save(version);
    }

    @Transactional
    public WorkDefinitionVersion approveImportedPendingVersion(String logicalIdentifier,
                                                              int versionNumber,
                                                              UUID reviewerUserId) {
        return reviewImportedPendingVersion(logicalIdentifier, versionNumber, reviewerUserId, true);
    }

    @Transactional
    public WorkDefinitionVersion rejectImportedPendingVersion(String logicalIdentifier,
                                                             int versionNumber,
                                                             UUID reviewerUserId) {
        return reviewImportedPendingVersion(logicalIdentifier, versionNumber, reviewerUserId, false);
    }

    private WorkDefinitionVersion reviewImportedPendingVersion(String logicalIdentifier,
                                                              int versionNumber,
                                                              UUID reviewerUserId,
                                                              boolean approve) {
        String normalizedLogicalIdentifier = DefinitionValidation.requireValidLogicalIdentifier(logicalIdentifier);
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be greater than or equal to 1.");
        }
        UUID reviewerId = DefinitionValidation.requireUserId(reviewerUserId, "reviewerUserId");

        WorkDefinition definition = definitionRepository.findByLogicalIdentifierForUpdate(normalizedLogicalIdentifier)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Work definition not found: " + normalizedLogicalIdentifier
                ));
        requireSourceType(definition, DefinitionSourceType.IMPORTED);

        WorkDefinitionVersion version = versionRepository.findByDefinitionAndVersionNumber(definition, versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Work definition version not found: " + normalizedLogicalIdentifier + "#" + versionNumber
                ));

        if (approve) {
            version.approve(LocalDateTime.now(), reviewerId);
        } else {
            version.reject(LocalDateTime.now(), reviewerId);
        }

        return version;
    }

    private void ensureLogicalIdentifierIsAvailable(String logicalIdentifier) {
        if (definitionRepository.existsByLogicalIdentifier(logicalIdentifier)) {
            throw new DuplicateResourceException("logicalIdentifier", logicalIdentifier);
        }
    }

    private WorkDefinition findDefinitionForVersionCreation(DefinitionContent content,
                                                            DefinitionSourceType expectedSourceType) {
        WorkDefinition definition = definitionRepository.findByLogicalIdentifierForUpdate(content.logicalIdentifier())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Work definition not found: " + content.logicalIdentifier()
                ));

        requireSourceType(definition, expectedSourceType);
        requireWorkType(definition, content.workType());
        return definition;
    }

    private static void requireSourceType(WorkDefinition definition, DefinitionSourceType expectedSourceType) {
        if (definition.getSourceType() != expectedSourceType) {
            throw new IllegalStateException(
                    "Work definition source type must be " + expectedSourceType + "."
            );
        }
    }

    private static void requireWorkType(WorkDefinition definition, WorkType requestedWorkType) {
        if (definition.getWorkType() != requestedWorkType) {
            throw new IllegalStateException("Work definition type is immutable.");
        }
    }

    private int nextVersionNumber(WorkDefinition definition) {
        return versionRepository.findHighestVersionNumber(definition) + 1;
    }

    private WorkDefinitionVersion createLocalVersion(WorkDefinition definition,
                                                    int versionNumber,
                                                    DefinitionContent content,
                                                    LocalDateTime createdAt) {
        return WorkDefinitionVersion.createLocal(
                definition,
                versionNumber,
                content.name(),
                content.description(),
                content.executorId(),
                content.executorContractVersion(),
                content.executorConfiguration(),
                content.defaultResourceRequest(),
                checksum(content),
                createdAt,
                content.actorUserId()
        );
    }

    private WorkDefinitionVersion createImportedVersion(WorkDefinition definition,
                                                       int versionNumber,
                                                       DefinitionContent content,
                                                       LocalDateTime createdAt) {
        return WorkDefinitionVersion.createImported(
                definition,
                versionNumber,
                content.name(),
                content.description(),
                content.executorId(),
                content.executorContractVersion(),
                content.executorConfiguration(),
                content.defaultResourceRequest(),
                checksum(content),
                createdAt,
                content.actorUserId()
        );
    }

    private String checksum(DefinitionContent content) {
        return checksumService.calculateChecksum(
                content.logicalIdentifier(),
                content.workType(),
                content.name(),
                content.description(),
                content.executorId(),
                content.executorContractVersion(),
                content.executorConfiguration(),
                content.defaultResourceRequest()
        );
    }

    private static DefinitionContent validateContent(DefinitionContentCommand command) {
        Objects.requireNonNull(command, "command must not be null.");

        String logicalIdentifier = DefinitionValidation.requireValidLogicalIdentifier(command.logicalIdentifier());
        WorkType workType = Objects.requireNonNull(command.workType(), "workType must not be null.");
        String name = DefinitionValidation.requireNonBlankName(command.name());
        String executorId = DefinitionValidation.requireValidExecutorId(command.executorId());
        DefinitionValidation.requirePositiveExecutorContractVersion(command.executorContractVersion());
        JsonNode executorConfiguration = DefinitionValidation.requireObjectConfiguration(
                command.executorConfiguration()
        ).deepCopy();
        ResourceRequest defaultResourceRequest = Objects.requireNonNull(
                command.defaultResourceRequest(),
                "defaultResourceRequest must not be null."
        );
        UUID actorUserId = DefinitionValidation.requireUserId(command.actorUserId(), "actorUserId");

        return new DefinitionContent(
                logicalIdentifier,
                workType,
                name,
                command.description(),
                executorId,
                command.executorContractVersion(),
                executorConfiguration,
                defaultResourceRequest,
                actorUserId
        );
    }

    private record DefinitionContent(
            String logicalIdentifier,
            WorkType workType,
            String name,
            String description,
            String executorId,
            int executorContractVersion,
            JsonNode executorConfiguration,
            ResourceRequest defaultResourceRequest,
            UUID actorUserId
    ) {
    }
}
