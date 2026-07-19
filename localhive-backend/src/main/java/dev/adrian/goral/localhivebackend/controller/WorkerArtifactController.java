package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.dto.ExecutionOutputArtifactResponseDto;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactDownload;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactManagementService;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactSizeLimitExceededException;
import dev.adrian.goral.localhivebackend.service.artifact.ExecutionOutputArtifactService;
import dev.adrian.goral.localhivebackend.service.work.ExecutionLeaseException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/workers/{workerId}/executions/{executionId}/artifacts")
@RequiredArgsConstructor
public class WorkerArtifactController {

    private static final String EXECUTION_LEASE_HEADER = "X-EXECUTION-LEASE";

    private final ArtifactManagementService artifactManagementService;
    private final ExecutionOutputArtifactService outputArtifactService;

    @PostMapping(path = "/output", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExecutionOutputArtifactResponseDto> uploadOutputArtifact(@PathVariable UUID workerId,
                                                                                   @PathVariable UUID executionId,
                                                                                   @RequestHeader(EXECUTION_LEASE_HEADER) String rawLeaseToken,
                                                                                   @RequestParam(value = "file", required = false) MultipartFile file,
                                                                                   @RequestParam(value = "relativePath", required = false) String relativePath) {
        try {
            ExecutionArtifact executionArtifact = outputArtifactService.uploadOutputArtifact(
                    workerId,
                    executionId,
                    file,
                    relativePath,
                    rawLeaseToken,
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(ExecutionOutputArtifactResponseDto.from(executionArtifact));
        } catch (ExecutionLeaseException e) {
            throw leaseResponse(e);
        } catch (ArtifactSizeLimitExceededException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<FileSystemResource> downloadWorkspacePackage(@PathVariable UUID workerId,
                                                                       @PathVariable UUID executionId,
                                                                       @PathVariable UUID artifactId,
                                                                       @RequestHeader(EXECUTION_LEASE_HEADER) String rawLeaseToken) {
        try {
            ArtifactDownload download = artifactManagementService.resolveWorkspacePackageDownload(
                    workerId,
                    executionId,
                    artifactId,
                    rawLeaseToken,
                    LocalDateTime.now()
            );
            Artifact artifact = download.artifact();
            FileSystemResource resource = new FileSystemResource(download.path());

            return ResponseEntity.ok()
                    .contentType(contentType(artifact))
                    .contentLength(artifact.getSizeBytes())
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename(artifact.getOriginalFilename(), StandardCharsets.UTF_8)
                                    .build()
                                    .toString()
                    )
                    .body(resource);
        } catch (ExecutionLeaseException e) {
            throw leaseResponse(e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static MediaType contentType(Artifact artifact) {
        if (artifact.getContentType() == null || artifact.getContentType().isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        try {
            return MediaType.parseMediaType(artifact.getContentType());
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static ResponseStatusException leaseResponse(ExecutionLeaseException exception) {
        HttpStatus status = switch (exception.getReason()) {
            case EXPIRED, INVALID_STATUS -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.FORBIDDEN;
        };
        return new ResponseStatusException(status, exception.getMessage());
    }
}
