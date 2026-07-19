package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.service.work.ExecutionLeaseValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ExecutionOutputArtifactService {

    public static final long MAX_OUTPUT_ARTIFACT_SIZE_BYTES = 50L * 1024 * 1024;

    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");
    private static final EnumSet<WorkExecutionStatus> UPLOAD_ALLOWED_STATUSES = EnumSet.of(
            WorkExecutionStatus.CLAIMED,
            WorkExecutionStatus.RUNNING
    );

    private final ArtifactRepository artifactRepository;
    private final ExecutionArtifactRepository executionArtifactRepository;
    private final WorkExecutionRepository executionRepository;
    private final ArtifactStorageService storageService;
    private final ExecutionLeaseValidationService leaseValidationService;
    private final long maxOutputArtifactSizeBytes;

    public ExecutionOutputArtifactService(ArtifactRepository artifactRepository,
                                          ExecutionArtifactRepository executionArtifactRepository,
                                          WorkExecutionRepository executionRepository,
                                          ArtifactStorageService storageService,
                                          ExecutionLeaseValidationService leaseValidationService,
                                          @Value("${localhive.artifacts.max-output-size-bytes:52428800}") long maxOutputArtifactSizeBytes) {
        if (maxOutputArtifactSizeBytes < 1) {
            throw new IllegalArgumentException("maxOutputArtifactSizeBytes must be positive.");
        }
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository must not be null.");
        this.executionArtifactRepository = Objects.requireNonNull(
                executionArtifactRepository,
                "executionArtifactRepository must not be null."
        );
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository must not be null.");
        this.storageService = Objects.requireNonNull(storageService, "storageService must not be null.");
        this.leaseValidationService = Objects.requireNonNull(
                leaseValidationService,
                "leaseValidationService must not be null."
        );
        this.maxOutputArtifactSizeBytes = maxOutputArtifactSizeBytes;
    }

    @Transactional
    public ExecutionArtifact uploadOutputArtifact(UUID workerId,
                                                  UUID executionId,
                                                  MultipartFile file,
                                                  String relativePath,
                                                  String rawLeaseToken,
                                                  LocalDateTime now) {
        MultipartFile validFile = requireFile(file);
        LocalDateTime createdAt = Objects.requireNonNull(now, "now must not be null.");
        String originalFilename = sanitizeOriginalFilename(validFile.getOriginalFilename());
        String normalizedRelativePath = normalizeRelativePath(relativePath, originalFilename);
        String contentType = normalizeContentType(validFile.getContentType());
        requireAllowedSize(validFile.getSize());

        ExecutionAssignment assignment = leaseValidationService.validateLease(
                workerId,
                executionId,
                rawLeaseToken,
                createdAt,
                UPLOAD_ALLOWED_STATUSES
        );
        UUID artifactId = UUID.randomUUID();
        StoredArtifact storedArtifact = storageService.storeExecutionOutput(
                artifactId,
                validFile,
                maxOutputArtifactSizeBytes
        );

        try {
            Artifact artifact = Artifact.create(
                    artifactId,
                    ArtifactKind.EXECUTION_OUTPUT,
                    originalFilename,
                    contentType,
                    storedArtifact.sizeBytes(),
                    storedArtifact.sha256(),
                    storedArtifact.storagePath(),
                    createdAt,
                    workerId.toString()
            );
            Artifact savedArtifact = artifactRepository.save(artifact);
            ExecutionArtifact executionArtifact = ExecutionArtifact.create(
                    assignment.getExecution(),
                    savedArtifact,
                    assignment.getWorker(),
                    normalizedRelativePath,
                    createdAt
            );
            return executionArtifactRepository.save(executionArtifact);
        } catch (RuntimeException e) {
            storageService.deleteQuietly(storedArtifact.storagePath());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<ExecutionArtifact> listOutputArtifacts(UUID executionId) {
        UUID validExecutionId = Objects.requireNonNull(executionId, "executionId must not be null.");
        if (!executionRepository.existsById(validExecutionId)) {
            throw new IllegalArgumentException("Execution not found.");
        }

        return executionArtifactRepository.findByExecution_IdAndArtifact_KindOrderByCreatedAtAsc(
                validExecutionId,
                ArtifactKind.EXECUTION_OUTPUT
        );
    }

    @Transactional(readOnly = true)
    public ExecutionArtifactDownload resolveOutputArtifactDownload(UUID artifactId) {
        UUID validArtifactId = Objects.requireNonNull(artifactId, "artifactId must not be null.");
        ExecutionArtifact executionArtifact = executionArtifactRepository.findByArtifact_Id(validArtifactId)
                .filter(candidate -> candidate.getArtifact().getKind() == ArtifactKind.EXECUTION_OUTPUT)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found."));

        return new ExecutionArtifactDownload(
                executionArtifact,
                storageService.resolveReadablePath(executionArtifact.getArtifact())
        );
    }

    private MultipartFile requireFile(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("file is required.");
        }

        return file;
    }

    private static String sanitizeOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("file original filename is required.");
        }
        if (originalFilename.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("file original filename cannot contain a null byte.");
        }
        if (originalFilename.indexOf('\r') >= 0 || originalFilename.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("file original filename cannot contain line breaks.");
        }

        String filename = StringUtils.getFilename(originalFilename.replace('\\', '/'));
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("file original filename is required.");
        }
        String normalizedFilename = filename.trim();
        if (normalizedFilename.length() > 255) {
            throw new IllegalArgumentException("file original filename must be at most 255 characters.");
        }

        return normalizedFilename;
    }

    private String normalizeRelativePath(String relativePath, String fallbackFilename) {
        String candidate = relativePath == null
                ? fallbackFilename
                : relativePath.trim();
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("relativePath is required.");
        }
        if (candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("relativePath cannot contain a null byte.");
        }
        if (candidate.startsWith("/") || candidate.startsWith("\\")) {
            throw new IllegalArgumentException("relativePath must be relative.");
        }
        if (WINDOWS_DRIVE_PATH.matcher(candidate).matches()) {
            throw new IllegalArgumentException("relativePath cannot use a Windows drive path.");
        }

        String normalizedSeparators = candidate.replace('\\', '/');
        if (normalizedSeparators.length() > 1024) {
            throw new IllegalArgumentException("relativePath must be at most 1024 characters.");
        }

        String[] segments = normalizedSeparators.split("/", -1);
        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("relativePath cannot contain blank path segments.");
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("relativePath cannot contain parent traversal.");
            }
            if (".".equals(segment)) {
                continue;
            }
            normalizedSegments.add(segment);
        }

        String normalized = String.join("/", normalizedSegments);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("relativePath is required.");
        }
        if (normalized.length() > 1024) {
            throw new IllegalArgumentException("relativePath must be at most 1024 characters.");
        }

        return normalized;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }

        String normalizedContentType = contentType.trim();
        if (normalizedContentType.length() > 255) {
            throw new IllegalArgumentException("file content type must be at most 255 characters.");
        }

        return normalizedContentType;
    }

    private void requireAllowedSize(long declaredSizeBytes) {
        if (declaredSizeBytes < 0) {
            throw new IllegalArgumentException("file size must not be negative.");
        }
        if (declaredSizeBytes > maxOutputArtifactSizeBytes) {
            throw new ArtifactSizeLimitExceededException("file must be at most 50 MB.");
        }
    }
}
