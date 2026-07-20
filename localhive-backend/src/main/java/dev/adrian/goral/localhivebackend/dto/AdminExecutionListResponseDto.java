package dev.adrian.goral.localhivebackend.dto;

import java.util.List;

public record AdminExecutionListResponseDto(
        List<AdminExecutionSummaryResponseDto> items,
        int limit,
        int offset,
        long totalCount
) {

    public AdminExecutionListResponseDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
