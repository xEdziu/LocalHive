package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminExecutionArtifactResponseDto(
        UUID artifactId,
        ArtifactKind kind,
        UUID executionId,
        UUID uploadedByWorkerId,
        String relativePath,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        LocalDateTime createdAt
) {

    public static AdminExecutionArtifactResponseDto from(ExecutionArtifact executionArtifact) {
        Artifact artifact = executionArtifact.getArtifact();
        return new AdminExecutionArtifactResponseDto(
                artifact.getId(),
                artifact.getKind(),
                executionArtifact.getExecution().getId(),
                executionArtifact.getUploadedByWorker().getId(),
                executionArtifact.getRelativePath(),
                artifact.getOriginalFilename(),
                artifact.getContentType(),
                artifact.getSizeBytes(),
                artifact.getSha256(),
                artifact.getCreatedAt()
        );
    }
}
