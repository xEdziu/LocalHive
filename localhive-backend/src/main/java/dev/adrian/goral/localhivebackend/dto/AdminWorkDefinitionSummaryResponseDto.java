package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminWorkDefinitionSummaryResponseDto(
        UUID definitionId,
        String logicalId,
        String type,
        String sourceType,
        String name,
        String description,
        Integer latestVersion,
        int versionCount,
        UUID latestVersionId,
        String latestExecutorId,
        Integer latestExecutorContractVersion,
        String latestApprovalStatus,
        LocalDateTime createdAt
) {
}
