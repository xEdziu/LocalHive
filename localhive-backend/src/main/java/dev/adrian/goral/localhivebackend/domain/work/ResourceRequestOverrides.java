package dev.adrian.goral.localhivebackend.domain.work;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Embeddable
@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResourceRequestOverrides {

    @Column(name = "override_required_ram_mb")
    private Integer requiredRamMb;

    @Column(name = "override_required_cpu_cores")
    private Integer requiredCpuCores;

    @Column(name = "override_gpu_required")
    private Boolean gpuRequired;

    private ResourceRequestOverrides(Integer requiredRamMb, Integer requiredCpuCores, Boolean gpuRequired) {
        this.requiredRamMb = requireNonNegativeIfPresent(requiredRamMb, "requiredRamMb");
        this.requiredCpuCores = requireNonNegativeIfPresent(requiredCpuCores, "requiredCpuCores");
        this.gpuRequired = gpuRequired;
    }

    public static ResourceRequestOverrides of(Integer requiredRamMb, Integer requiredCpuCores, Boolean gpuRequired) {
        return new ResourceRequestOverrides(requiredRamMb, requiredCpuCores, gpuRequired);
    }

    public static ResourceRequestOverrides empty() {
        return of(null, null, null);
    }

    private static Integer requireNonNegativeIfPresent(Integer value, String fieldName) {
        if (value != null) {
            ResourceRequest.requireNonNegative(value, fieldName);
        }

        return value;
    }
}
