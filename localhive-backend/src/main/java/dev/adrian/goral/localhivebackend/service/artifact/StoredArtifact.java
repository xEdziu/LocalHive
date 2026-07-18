package dev.adrian.goral.localhivebackend.service.artifact;

public record StoredArtifact(
        String storagePath,
        long sizeBytes,
        String sha256
) {
}
