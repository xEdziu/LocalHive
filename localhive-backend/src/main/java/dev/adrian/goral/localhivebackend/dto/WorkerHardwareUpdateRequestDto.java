package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.validation.IpAddress;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkerHardwareUpdateRequestDto {

    @Size(max = 63, message = "Hostname must be at most 63 characters")
    @Pattern(
            regexp = "^(?!-)[A-Za-z0-9-]{1,63}(?<!-)$",
            message = "Hostname format is invalid"
    )
    private String hostname;

    @IpAddress
    private String ipAddress;

    @Size(max = 50, message = "OS type must be at most 50 characters")
    @Pattern(
            regexp = "^[A-Za-z0-9 ._\\-]+$",
            message = "OS type contains invalid characters"
    )
    private String osType;

    @Min(value = 1, message = "Total RAM must be at least 1 MB")
    @Max(value = 10_485_760, message = "Total RAM is unrealistically high")
    private Integer totalRamMb;

    @Min(value = 0, message = "Shared RAM cannot be negative")
    @Max(value = 10_485_760, message = "Shared RAM is unrealistically high")
    private Integer sharedRamMb;

    @Min(value = 1, message = "CPU must have at least 1 core")
    @Max(value = 1024, message = "CPU core count is unrealistically high")
    private Integer cpuCores;

    @Size(max = 120, message = "GPU name must be at most 120 characters")
    @Pattern(
            regexp = "^[\\p{L}\\p{N} ._\\-()]*$",
            message = "GPU name contains invalid characters"
    )
    private String gpuName;
}
