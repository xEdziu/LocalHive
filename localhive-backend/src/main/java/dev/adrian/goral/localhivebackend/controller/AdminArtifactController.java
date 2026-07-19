package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.dto.AdminExecutionArtifactResponseDto;
import dev.adrian.goral.localhivebackend.service.artifact.ExecutionArtifactDownload;
import dev.adrian.goral.localhivebackend.service.artifact.ExecutionOutputArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminArtifactController {

    private final ExecutionOutputArtifactService outputArtifactService;

    @GetMapping("/executions/{executionId}/artifacts")
    public ResponseEntity<List<AdminExecutionArtifactResponseDto>> listExecutionArtifacts(@PathVariable UUID executionId) {
        try {
            List<AdminExecutionArtifactResponseDto> artifacts = outputArtifactService.listOutputArtifacts(executionId)
                    .stream()
                    .map(AdminExecutionArtifactResponseDto::from)
                    .toList();
            return ResponseEntity.ok(artifacts);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<FileSystemResource> downloadExecutionOutputArtifact(@PathVariable UUID artifactId) {
        try {
            ExecutionArtifactDownload download = outputArtifactService.resolveOutputArtifactDownload(artifactId);
            ExecutionArtifact executionArtifact = download.executionArtifact();
            Artifact artifact = executionArtifact.getArtifact();
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
}
