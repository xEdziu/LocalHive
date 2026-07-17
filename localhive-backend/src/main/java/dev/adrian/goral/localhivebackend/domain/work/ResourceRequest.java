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
public class ResourceRequest {

    @Column(name = "required_ram_mb", nullable = false)
    private int requiredRamMb;

    @Column(name = "required_cpu_cores", nullable = false)
    private int requiredCpuCores;

    @Column(name = "gpu_required", nullable = false)
    private boolean gpuRequired;

    private ResourceRequest(int requiredRamMb, int requiredCpuCores, boolean gpuRequired) {
        this.requiredRamMb = requireNonNegative(requiredRamMb, "requiredRamMb");
        this.requiredCpuCores = requireNonNegative(requiredCpuCores, "requiredCpuCores");
        this.gpuRequired = gpuRequired;
    }

    public static ResourceRequest of(int requiredRamMb, int requiredCpuCores, boolean gpuRequired) {
        return new ResourceRequest(requiredRamMb, requiredCpuCores, gpuRequired);
    }

    public static ResourceRequest zero() {
        return of(0, 0, false);
    }

    static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than or equal to 0.");
        }

        return value;
    }
}
