package dev.adrian.goral.localhivebackend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WorkerAllocationUpdateRequestDto {

    @NotNull(message = "Shared RAM must be provided")
    @Min(value = 0, message = "Shared RAM cannot be negative")
    @Max(value = 10_485_760, message = "Shared RAM is unrealistically high")
    private Integer sharedRamMb;
}
