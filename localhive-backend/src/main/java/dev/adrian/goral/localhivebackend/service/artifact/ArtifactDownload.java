package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;

import java.nio.file.Path;

public record ArtifactDownload(
        Artifact artifact,
        Path path
) {
}
