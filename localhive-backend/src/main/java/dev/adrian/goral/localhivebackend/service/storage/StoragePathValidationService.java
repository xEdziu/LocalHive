package dev.adrian.goral.localhivebackend.service.storage;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Service
public class StoragePathValidationService {

    private static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private static final String PROBE_PREFIX = ".localhive-write-probe-";

    public StorageLayout prepareDataRoot(String dataRoot) {
        Path root = validateDataRoot(dataRoot);
        StorageLayout layout = new StorageLayout(
                root,
                root.resolve("artifacts").normalize(),
                root.resolve("temp").normalize(),
                root.resolve("snapshots").normalize(),
                root.resolve("logs").normalize()
        );
        rejectUnsafeExistingPathChains(layout);
        createDirectories(layout);
        rejectUnsafeExistingPathChains(layout);
        verifyWritable(layout);
        return layout;
    }

    private Path validateDataRoot(String dataRoot) {
        if (dataRoot == null) {
            throw new IllegalArgumentException("dataRoot is required.");
        }
        String trimmed = dataRoot.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("dataRoot must not be blank.");
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("dataRoot cannot contain a null byte.");
        }

        Path candidate;
        try {
            candidate = Path.of(trimmed);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("dataRoot path is invalid.", e);
        }

        if (!candidate.isAbsolute()) {
            throw new IllegalArgumentException("dataRoot must be an absolute path.");
        }
        if (containsParentTraversal(candidate)) {
            throw new IllegalArgumentException("dataRoot cannot contain parent traversal.");
        }

        Path normalized = candidate.normalize();
        if (normalized.equals(normalized.getRoot())) {
            throw new IllegalArgumentException("dataRoot cannot be the filesystem root.");
        }
        if (isUnsafeSystemDirectory(normalized)) {
            throw new IllegalArgumentException("dataRoot cannot point to a system directory.");
        }

        rejectUnsafeExistingPathChain(normalized);
        return normalized;
    }

    private static boolean containsParentTraversal(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void rejectUnsafeExistingPathChain(Path path) {
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("dataRoot must not include symbolic links.");
            }
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("dataRoot existing path segments must be directories.");
            }
        }
    }

    private static void rejectUnsafeExistingPathChains(StorageLayout layout) {
        for (Path directory : layout.requiredDirectories()) {
            rejectUnsafeExistingPathChain(directory);
        }
    }

    private static boolean isUnsafeSystemDirectory(Path path) {
        if (isWindows()) {
            String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return normalized.matches("^[a-z]:/windows(/.*)?$")
                    || normalized.matches("^[a-z]:/program files(/.*)?$")
                    || normalized.matches("^[a-z]:/program files \\(x86\\)(/.*)?$");
        }

        return path.startsWith(Path.of("/bin"))
                || path.startsWith(Path.of("/etc"))
                || path.startsWith(Path.of("/usr"))
                || path.startsWith(Path.of("/var"))
                || path.startsWith(Path.of("/root"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void createDirectories(StorageLayout layout) {
        try {
            for (Path directory : layout.requiredDirectories()) {
                Files.createDirectories(directory);
            }
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("dataRoot directories cannot be created.", e);
        }
    }

    private static void verifyWritable(StorageLayout layout) {
        for (Path directory : layout.requiredDirectories()) {
            writeAndDeleteProbe(directory);
        }
    }

    private static void writeAndDeleteProbe(Path directory) {
        Path probe = directory.resolve(PROBE_PREFIX + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(
                    probe,
                    "ok",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            deleteProbeQuietly(probe);
            throw new IllegalArgumentException("dataRoot directories must be writable.", e);
        }
    }

    private static void deleteProbeQuietly(Path probe) {
        try {
            Files.deleteIfExists(probe);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed probe.
        }
    }
}
