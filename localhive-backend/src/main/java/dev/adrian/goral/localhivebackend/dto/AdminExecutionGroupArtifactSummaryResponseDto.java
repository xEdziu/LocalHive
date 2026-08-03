package dev.adrian.goral.localhivebackend.dto;

public record AdminExecutionGroupArtifactSummaryResponseDto(
        long totalArtifacts,
        long shardArtifacts,
        long mergeArtifacts,
        long shardsWithArtifacts,
        boolean mergeHasArtifacts,
        String preferredOutputSource
) {
}
