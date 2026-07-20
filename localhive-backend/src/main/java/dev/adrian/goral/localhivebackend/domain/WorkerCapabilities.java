package dev.adrian.goral.localhivebackend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "worker_capabilities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
public class WorkerCapabilities {

    @Id
    @Column(name = "worker_id", nullable = false)
    private UUID workerId;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executors", nullable = false, columnDefinition = "jsonb")
    private JsonNode executors;

    @Column(name = "docker_enabled")
    private Boolean dockerEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "docker_allowed_images", columnDefinition = "jsonb")
    private JsonNode dockerAllowedImages;

    @Column(name = "docker_max_memory_mb")
    private Integer dockerMaxMemoryMb;

    @Column(name = "docker_max_cpu_cores")
    private Integer dockerMaxCpuCores;

    @Column(name = "docker_gpu_allowed")
    private Boolean dockerGpuAllowed;

    private WorkerCapabilities(Worker worker) {
        this.workerId = Objects.requireNonNull(
                Objects.requireNonNull(worker, "worker is required").getId(),
                "workerId is required"
        );
    }

    public static WorkerCapabilities create(Worker worker) {
        return new WorkerCapabilities(worker);
    }

    public void replaceWith(
            LocalDateTime reportedAt,
            JsonNode executors,
            Boolean dockerEnabled,
            JsonNode dockerAllowedImages,
            Integer dockerMaxMemoryMb,
            Integer dockerMaxCpuCores,
            Boolean dockerGpuAllowed
    ) {
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt is required");
        this.executors = Objects.requireNonNull(executors, "executors is required").deepCopy();
        this.dockerEnabled = dockerEnabled;
        this.dockerAllowedImages = dockerAllowedImages == null ? null : dockerAllowedImages.deepCopy();
        this.dockerMaxMemoryMb = dockerMaxMemoryMb;
        this.dockerMaxCpuCores = dockerMaxCpuCores;
        this.dockerGpuAllowed = dockerGpuAllowed;
    }
}
