package dev.adrian.goral.localhivebackend.dto;

import java.util.List;

public record AdminBenchmarkRunCreateRequestDto(
        String displayName,
        String description,
        List<String> tags,
        String notes
) {
}
