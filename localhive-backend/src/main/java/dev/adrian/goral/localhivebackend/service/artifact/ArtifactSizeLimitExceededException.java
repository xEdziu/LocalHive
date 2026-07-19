package dev.adrian.goral.localhivebackend.service.artifact;

public class ArtifactSizeLimitExceededException extends RuntimeException {

    public ArtifactSizeLimitExceededException(String message) {
        super(message);
    }
}
