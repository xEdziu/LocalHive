package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;

import java.util.UUID;

public record CreateWorkInstanceCommand(
        UUID definitionVersionId,
        String displayName,
        JsonNode configurationOverrides,
        ResourceRequestOverrides resourceOverrides
) {
}
