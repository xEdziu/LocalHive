package dev.adrian.goral.localhivebackend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WorkerHeartbeatRequestDto(
        @NotNull(message = "pauseEnabled is required")

        Boolean pauseEnabled,

        @NotNull(message = "sharedRamMb is required")
        @Min(value = 0, message = "sharedRamMb cannot be negative")
        @Max(value = 10_485_760, message = "sharedRamMb is unrealistically high")
        Integer sharedRamMb
) {
}
