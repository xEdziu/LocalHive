package dev.adrian.goral.localhivebackend.dto;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;

import java.time.LocalDateTime;
import java.util.UUID;

public record ArtifactResponseDto(
        UUID artifactId,
        ArtifactKind kind,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        LocalDateTime createdAt
) {

    public static ArtifactResponseDto from(Artifact artifact) {
        return new ArtifactResponseDto(
                artifact.getId(),
                artifact.getKind(),
                artifact.getOriginalFilename(),
                artifact.getContentType(),
                artifact.getSizeBytes(),
                artifact.getSha256(),
                artifact.getCreatedAt()
        );
    }
}
