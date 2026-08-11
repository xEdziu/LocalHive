package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultExpectedSystemBehavior;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultInjectionMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultScenarioType;
import dev.adrian.goral.localhivebackend.domain.research.ResearchFaultSeverity;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExecutionShape;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadType;

import java.util.List;

public record ResearchFaultScenarioDescriptor(
        String id,
        String name,
        ResearchFaultScenarioType type,
        ResearchFaultSeverity severity,
        ResearchFaultInjectionMode injectionMode,
        ResearchFaultExpectedSystemBehavior expectedSystemBehavior,
        List<ResearchProtocol> recommendedProtocols,
        List<ResearchOperation> recommendedOperations,
        List<ResearchWorkloadType> compatibleWorkloadTypes,
        List<ResearchWorkloadExecutionShape> compatibleExecutionShapes,
        String description,
        String researchPurpose,
        boolean requiresExistingExecutionGroup,
        boolean requiresRunningWorker,
        boolean requiresDocker,
        boolean requiresManualAction,
        List<String> tags
) {

    public ResearchFaultScenarioDescriptor {
        recommendedProtocols = recommendedProtocols == null ? List.of() : List.copyOf(recommendedProtocols);
        recommendedOperations = recommendedOperations == null ? List.of() : List.copyOf(recommendedOperations);
        compatibleWorkloadTypes = compatibleWorkloadTypes == null ? List.of() : List.copyOf(compatibleWorkloadTypes);
        compatibleExecutionShapes = compatibleExecutionShapes == null
                ? List.of()
                : List.copyOf(compatibleExecutionShapes);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
