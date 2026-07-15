package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record DefinitionContentCommand(
        String logicalIdentifier,
        WorkType workType,
        String name,
        String description,
        String executorId,
        int executorContractVersion,
        JsonNode executorConfiguration,
        UUID actorUserId
) {
}
