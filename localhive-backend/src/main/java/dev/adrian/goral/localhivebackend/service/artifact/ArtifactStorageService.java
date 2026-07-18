package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ArtifactStorageService {

    private final Path storageRoot;

    public ArtifactStorageService(
            @Value("${localhive.artifacts.storage-root:.localhive-master/artifacts}") String storageRoot
    ) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredArtifact storeWorkspacePackage(UUID artifactId, MultipartFile file) {
        Path relativePath = Path.of(artifactId.toString(), "package.zip");
        Path targetPath = storageRoot.resolve(relativePath).normalize();
        if (!targetPath.startsWith(storageRoot)) {
            throw new IllegalStateException("Artifact storage path is invalid.");
        }
        if (Files.exists(targetPath)) {
            throw new IllegalStateException("Artifact file already exists.");
        }

        try {
            Files.createDirectories(targetPath.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long sizeBytes;
            try (DigestInputStream inputStream = new DigestInputStream(file.getInputStream(), digest)) {
                sizeBytes = Files.copy(inputStream, targetPath);
            }

            return new StoredArtifact(
                    relativePath.toString().replace('\\', '/'),
                    sizeBytes,
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("Artifact file already exists.", e);
        } catch (IOException e) {
            deleteQuietly(targetPath);
            throw new UncheckedIOException("Failed to store artifact.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", e);
        }
    }

    public Path resolveReadablePath(Artifact artifact) {
        Path resolvedPath = storageRoot.resolve(artifact.getStoragePath()).normalize();
        if (!resolvedPath.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Artifact not found.");
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IllegalArgumentException("Artifact not found.");
        }

        return resolvedPath;
    }

    public String storageRoot() {
        return storageRoot.toString();
    }

    public void deleteQuietly(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }

        Path resolvedPath = storageRoot.resolve(storagePath).normalize();
        if (!resolvedPath.startsWith(storageRoot)) {
            return;
        }

        deleteQuietly(resolvedPath);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed store.
        }
    }
}
