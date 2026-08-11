package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

import java.util.List;
import java.util.UUID;

public record AdminProtocolComparisonRunRequestDto(
        String displayName,
        String description,
        String workloadId,
        UUID targetExecutionGroupId,
        List<ResearchProtocol> protocols,
        List<ResearchOperation> operations,
        Integer repetitions,
        List<String> tags,
        String notes
) {
}
