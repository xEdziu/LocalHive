package dev.adrian.goral.localhivebackend.dto;

import java.util.List;

public record AdminExecutionGroupListResponseDto(
        List<AdminExecutionGroupSummaryResponseDto> items,
        int limit,
        int offset,
        long totalCount
) {

    public AdminExecutionGroupListResponseDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
