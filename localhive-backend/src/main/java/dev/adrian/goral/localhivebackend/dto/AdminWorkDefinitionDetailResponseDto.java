package dev.adrian.goral.localhivebackend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminWorkDefinitionDetailResponseDto(
        UUID definitionId,
        String logicalId,
        String type,
        String sourceType,
        String name,
        String description,
        LocalDateTime createdAt,
        List<VersionDto> versions
) {

    public AdminWorkDefinitionDetailResponseDto {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public record VersionDto(
            UUID versionId,
            int version,
            boolean latest,
            String name,
            String description,
            String executorId,
            int executorContractVersion,
            String approvalStatus,
            LocalDateTime createdAt
    ) {
    }
}
