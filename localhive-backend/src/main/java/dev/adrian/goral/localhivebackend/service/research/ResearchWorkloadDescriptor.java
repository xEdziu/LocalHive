package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadComplexity;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadDataProfile;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExecutionShape;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadExpectedOutcome;
import dev.adrian.goral.localhivebackend.domain.research.ResearchWorkloadType;

import java.util.List;

public record ResearchWorkloadDescriptor(
        String id,
        String name,
        ResearchWorkloadType type,
        ResearchWorkloadComplexity complexity,
        ResearchWorkloadExecutionShape executionShape,
        ResearchWorkloadExpectedOutcome expectedOutcome,
        List<ResearchWorkloadDataProfile> dataProfiles,
        List<ResearchProtocol> recommendedProtocols,
        List<ResearchOperation> recommendedOperations,
        String description,
        String researchPurpose,
        boolean requiresDocker,
        boolean requiresWorkspaceArtifact,
        boolean requiresMerge,
        Integer suggestedShardCount,
        Integer suggestedTimeoutSeconds,
        List<String> tags
) {

    public ResearchWorkloadDescriptor {
        dataProfiles = dataProfiles == null ? List.of() : List.copyOf(dataProfiles);
        recommendedProtocols = recommendedProtocols == null ? List.of() : List.copyOf(recommendedProtocols);
        recommendedOperations = recommendedOperations == null ? List.of() : List.copyOf(recommendedOperations);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
