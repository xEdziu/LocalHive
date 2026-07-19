package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;

import java.nio.file.Path;

public record ExecutionArtifactDownload(
        ExecutionArtifact executionArtifact,
        Path path
) {
}
