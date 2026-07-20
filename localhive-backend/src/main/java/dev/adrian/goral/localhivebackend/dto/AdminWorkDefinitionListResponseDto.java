package dev.adrian.goral.localhivebackend.dto;

import java.util.List;

public record AdminWorkDefinitionListResponseDto(
        List<AdminWorkDefinitionSummaryResponseDto> items,
        int limit,
        int offset,
        long totalCount
) {

    public AdminWorkDefinitionListResponseDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
