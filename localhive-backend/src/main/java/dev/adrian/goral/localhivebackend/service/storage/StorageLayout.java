package dev.adrian.goral.localhivebackend.service.storage;

import java.nio.file.Path;
import java.util.List;

public record StorageLayout(
        Path dataRoot,
        Path artifactsRoot,
        Path tempRoot,
        Path snapshotsRoot,
        Path logsRoot
) {

    public List<Path> requiredDirectories() {
        return List.of(dataRoot, artifactsRoot, tempRoot, snapshotsRoot, logsRoot);
    }
}
