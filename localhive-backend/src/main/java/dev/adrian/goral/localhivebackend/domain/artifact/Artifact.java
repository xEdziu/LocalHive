package dev.adrian.goral.localhivebackend.domain.artifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "artifacts")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Artifact {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ArtifactKind kind;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @ToString.Exclude
    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 255, updatable = false)
    private String createdBy;

    private Artifact(UUID id,
                     ArtifactKind kind,
                     String originalFilename,
                     String contentType,
                     long sizeBytes,
                     String sha256,
                     String storagePath,
                     LocalDateTime createdAt,
                     String createdBy) {
        this.id = Objects.requireNonNull(id, "id must not be null.");
        this.kind = Objects.requireNonNull(kind, "kind must not be null.");
        this.originalFilename = requireNonBlank(originalFilename, "originalFilename");
        this.contentType = requireNullableNonBlank(contentType, "contentType");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative.");
        }
        this.sizeBytes = sizeBytes;
        this.sha256 = requireSha256(sha256);
        this.storagePath = requireNonBlank(storagePath, "storagePath");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.createdBy = requireNullableNonBlank(createdBy, "createdBy");
    }

    public static Artifact create(UUID id,
                                  ArtifactKind kind,
                                  String originalFilename,
                                  String contentType,
                                  long sizeBytes,
                                  String sha256,
                                  String storagePath,
                                  LocalDateTime createdAt,
                                  String createdBy) {
        return new Artifact(
                id,
                kind,
                originalFilename,
                contentType,
                sizeBytes,
                sha256,
                storagePath,
                createdAt,
                createdBy
        );
    }

    private static String requireSha256(String value) {
        String sha256 = requireNonBlank(value, "sha256");
        if (sha256.length() != 64) {
            throw new IllegalArgumentException("sha256 must be exactly 64 characters.");
        }
        return sha256;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.trim();
    }

    private static String requireNullableNonBlank(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank when present.");
        }
        return value.trim();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        Artifact artifact = (Artifact) o;
        return getId() != null && Objects.equals(getId(), artifact.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
