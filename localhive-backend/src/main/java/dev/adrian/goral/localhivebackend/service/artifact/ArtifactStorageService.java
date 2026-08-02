package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.service.storage.StorageConfigurationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ArtifactStorageService {

    private final StorageConfigurationService storageConfigurationService;
    private final Path defaultStorageRoot;

    public ArtifactStorageService(
            StorageConfigurationService storageConfigurationService,
            @Value("${localhive.artifacts.storage-root:.localhive-master/artifacts}") String storageRoot
    ) {
        this.storageConfigurationService = storageConfigurationService;
        this.defaultStorageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredArtifact storeWorkspacePackage(UUID artifactId, MultipartFile file) {
        try {
            return storeWorkspacePackage(artifactId, file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read artifact.", e);
        }
    }

    public StoredArtifact storeWorkspacePackage(UUID artifactId, byte[] content) {
        return storeWorkspacePackage(artifactId, new ByteArrayInputStream(content));
    }

    private StoredArtifact storeWorkspacePackage(UUID artifactId, java.io.InputStream inputStream) {
        Path storageRoot = storageRootPath();
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
            try (DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                sizeBytes = Files.copy(digestInputStream, targetPath);
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

    public StoredArtifact storeExecutionOutput(UUID artifactId, MultipartFile file, long maxSizeBytes) {
        if (maxSizeBytes < 1) {
            throw new IllegalArgumentException("maxSizeBytes must be positive.");
        }

        Path storageRoot = storageRootPath();
        Path relativePath = Path.of(artifactId.toString(), "artifact");
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
            long sizeBytes = copyWithDigestAndLimit(file, targetPath, digest, maxSizeBytes);

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
        } catch (RuntimeException e) {
            deleteQuietly(targetPath);
            throw e;
        }
    }

    public Path resolveReadablePath(Artifact artifact) {
        Path storageRoot = storageRootPath();
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
        return storageRootPath().toString();
    }

    public void deleteQuietly(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }

        Path storageRoot = storageRootPath();
        Path resolvedPath = storageRoot.resolve(storagePath).normalize();
        if (!resolvedPath.startsWith(storageRoot)) {
            return;
        }

        deleteQuietly(resolvedPath);
    }

    private Path storageRootPath() {
        return storageConfigurationService.artifactsRoot(defaultStorageRoot);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed store.
        }
    }

    private static long copyWithDigestAndLimit(MultipartFile file,
                                               Path targetPath,
                                               MessageDigest digest,
                                               long maxSizeBytes) throws IOException {
        long sizeBytes = 0;
        byte[] buffer = new byte[8192];

        try (var inputStream = file.getInputStream();
             var outputStream = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                sizeBytes += read;
                if (sizeBytes > maxSizeBytes) {
                    throw new ArtifactSizeLimitExceededException("file must be at most 50 MB.");
                }
                digest.update(buffer, 0, read);
                outputStream.write(buffer, 0, read);
            }
        }

        return sizeBytes;
    }
}
