package dev.adrian.goral.localhivebackend.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkerExecutionFailureRequestDto(
        @NotBlank(message = "failureCode is required")
        String failureCode,

        String failureMessage
) {
}
