package dev.adrian.goral.localhivebackend.service.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.service.work.ExecutionLeaseValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArtifactManagementService {

    public static final long MAX_WORKSPACE_PACKAGE_SIZE_BYTES = 50L * 1024 * 1024;

    private static final Set<String> ALLOWED_WORKSPACE_CONTENT_TYPES = Set.of(
            "application/zip",
            "application/octet-stream"
    );
    private static final Set<WorkExecutionStatus> DOWNLOAD_ALLOWED_STATUSES = EnumSet.of(
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );

    private final ArtifactRepository artifactRepository;
    private final ArtifactStorageService storageService;
    private final ExecutionLeaseValidationService leaseValidationService;

    @Transactional
    public Artifact storeWorkspacePackage(MultipartFile file, String createdBy) {
        MultipartFile validFile = requireFile(file);
        String originalFilename = requireZipFilename(validFile.getOriginalFilename());
        String contentType = normalizeContentType(validFile.getContentType());
        long declaredSize = requireAllowedSize(validFile.getSize());
        UUID artifactId = UUID.randomUUID();

        StoredArtifact storedArtifact = storageService.storeWorkspacePackage(artifactId, validFile);
        try {
            if (storedArtifact.sizeBytes() > MAX_WORKSPACE_PACKAGE_SIZE_BYTES) {
                throw new IllegalArgumentException("file must be at most 50 MB.");
            }
            Artifact artifact = Artifact.create(
                    artifactId,
                    ArtifactKind.WORKSPACE_PACKAGE,
                    originalFilename,
                    contentType,
                    storedArtifact.sizeBytes(),
                    storedArtifact.sha256(),
                    storedArtifact.storagePath(),
                    LocalDateTime.now(),
                    createdBy
            );
            if (declaredSize != storedArtifact.sizeBytes()) {
                throw new IllegalArgumentException("Uploaded file size changed while storing.");
            }
            return artifactRepository.save(artifact);
        } catch (RuntimeException e) {
            storageService.deleteQuietly(storedArtifact.storagePath());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public ArtifactDownload resolveWorkspacePackageDownload(UUID workerId,
                                                            UUID executionId,
                                                            UUID artifactId,
                                                            String rawLeaseToken,
                                                            LocalDateTime now) {
        UUID validArtifactId = Objects.requireNonNull(artifactId, "artifactId must not be null.");
        ExecutionAssignment assignment = leaseValidationService.validateLease(
                workerId,
                executionId,
                rawLeaseToken,
                now,
                DOWNLOAD_ALLOWED_STATUSES
        );
        requireExecutionReferencesArtifact(assignment.getExecution().getResolvedConfigurationSnapshot(), validArtifactId);

        Artifact artifact = artifactRepository.findById(validArtifactId)
                .filter(candidate -> candidate.getKind() == ArtifactKind.WORKSPACE_PACKAGE)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found."));
        Path artifactPath = storageService.resolveReadablePath(artifact);

        return new ArtifactDownload(artifact, artifactPath);
    }

    private static MultipartFile requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required.");
        }

        return file;
    }

    private static String requireZipFilename(String filename) {
        String originalFilename = StringUtils.getFilename(filename);
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("file original filename is required.");
        }
        String normalizedFilename = originalFilename.trim();
        if (normalizedFilename.length() > 255) {
            throw new IllegalArgumentException("file original filename must be at most 255 characters.");
        }
        if (!normalizedFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("file must use .zip extension.");
        }

        return normalizedFilename;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }

        String normalizedContentType = contentType.trim();
        if (!ALLOWED_WORKSPACE_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new IllegalArgumentException("file content type must be application/zip or application/octet-stream.");
        }

        return normalizedContentType;
    }

    private static long requireAllowedSize(long sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("file size must not be negative.");
        }
        if (sizeBytes > MAX_WORKSPACE_PACKAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("file must be at most 50 MB.");
        }

        return sizeBytes;
    }

    private static void requireExecutionReferencesArtifact(JsonNode configuration, UUID artifactId) {
        JsonNode artifactIdNode = configuration.path("workspace").path("artifactId");
        if (!artifactIdNode.isTextual()) {
            throw new IllegalArgumentException("Artifact not found.");
        }

        try {
            if (!artifactId.equals(UUID.fromString(artifactIdNode.textValue()))) {
                throw new IllegalArgumentException("Artifact not found.");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Artifact not found.");
        }
    }
}
