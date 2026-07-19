package dev.adrian.goral.localhivebackend.domain.artifact;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "execution_artifacts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_execution_artifacts_artifact_id",
                columnNames = "artifact_id"
        )
)
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    @ToString.Exclude
    private WorkExecution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artifact_id", nullable = false, updatable = false)
    @ToString.Exclude
    private Artifact artifact;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_worker_id", nullable = false, updatable = false)
    @ToString.Exclude
    private Worker uploadedByWorker;

    @Column(name = "relative_path", nullable = false, length = 1024, updatable = false)
    private String relativePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ExecutionArtifact(WorkExecution execution,
                              Artifact artifact,
                              Worker uploadedByWorker,
                              String relativePath,
                              LocalDateTime createdAt) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null.");
        this.artifact = requireExecutionOutput(artifact);
        this.uploadedByWorker = Objects.requireNonNull(uploadedByWorker, "uploadedByWorker must not be null.");
        this.relativePath = requireRelativePath(relativePath);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
    }

    public static ExecutionArtifact create(WorkExecution execution,
                                           Artifact artifact,
                                           Worker uploadedByWorker,
                                           String relativePath,
                                           LocalDateTime createdAt) {
        return new ExecutionArtifact(execution, artifact, uploadedByWorker, relativePath, createdAt);
    }

    private static Artifact requireExecutionOutput(Artifact artifact) {
        Artifact validArtifact = Objects.requireNonNull(artifact, "artifact must not be null.");
        if (validArtifact.getKind() != ArtifactKind.EXECUTION_OUTPUT) {
            throw new IllegalArgumentException("artifact must be EXECUTION_OUTPUT.");
        }

        return validArtifact;
    }

    private static String requireRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank.");
        }
        String normalized = relativePath.trim();
        if (normalized.length() > 1024) {
            throw new IllegalArgumentException("relativePath must be at most 1024 characters.");
        }

        return normalized;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        ExecutionArtifact that = (ExecutionArtifact) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
