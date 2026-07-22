package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;

import java.util.UUID;

record AdminExecutionRequestPlan(
        WorkDefinitionVersion definitionVersion,
        ExecutionAssignmentMode assignmentMode,
        UUID requestedWorkerId,
        JsonNode configurationOverrides,
        ResourceRequestOverrides resourceOverrides,
        ResourceRequest requestedResources,
        WorkerSelectionCriteria selectionCriteria
) {
}
