package dev.adrian.goral.localhivebackend.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StoragePathValidationServiceTest {

    private final StoragePathValidationService validationService = new StoragePathValidationService();

    @TempDir
    private Path tempDir;

    @Test
    void shouldPrepareDataRootAndSubdirectories() {
        Path dataRoot = tempDir.resolve("localhive-data");

        StorageLayout layout = validationService.prepareDataRoot(dataRoot.toString());

        assertThat(layout.dataRoot()).isEqualTo(dataRoot.toAbsolutePath().normalize());
        assertThat(Files.isDirectory(layout.dataRoot())).isTrue();
        assertThat(Files.isDirectory(layout.artifactsRoot())).isTrue();
        assertThat(Files.isDirectory(layout.tempRoot())).isTrue();
        assertThat(Files.isDirectory(layout.snapshotsRoot())).isTrue();
        assertThat(Files.isDirectory(layout.logsRoot())).isTrue();
    }

    @Test
    void shouldRejectBlankDataRoot() {
        assertThatThrownBy(() -> validationService.prepareDataRoot("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot must not be blank.");
    }

    @Test
    void shouldRejectRelativeDataRoot() {
        assertThatThrownBy(() -> validationService.prepareDataRoot("relative/path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot must be an absolute path.");
    }

    @Test
    void shouldRejectParentTraversal() {
        Path parentTraversal = tempDir.resolve("data").resolve("..").resolve("other");

        assertThatThrownBy(() -> validationService.prepareDataRoot(parentTraversal.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot cannot contain parent traversal.");
    }

    @Test
    void shouldRejectNullByte() {
        String invalidPath = tempDir + File.separator + "bad\u0000path";

        assertThatThrownBy(() -> validationService.prepareDataRoot(invalidPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot cannot contain a null byte.");
    }

    @Test
    void shouldRejectExistingRegularFile() throws Exception {
        Path existingFile = Files.createFile(tempDir.resolve("file"));

        assertThatThrownBy(() -> validationService.prepareDataRoot(existingFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot existing path segments must be directories.");
    }

    @Test
    void shouldRejectFilesystemRoot() {
        Path root = tempDir.toAbsolutePath().getRoot();
        assumeTrue(root != null);

        assertThatThrownBy(() -> validationService.prepareDataRoot(root.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot cannot be the filesystem root.");
    }

    @Test
    void shouldRejectUnsafeSystemDirectory() {
        Path unsafePath = isWindows()
                ? Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                : Path.of("/etc");
        assumeTrue(unsafePath.isAbsolute());

        assertThatThrownBy(() -> validationService.prepareDataRoot(unsafePath.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot cannot point to a system directory.");
    }

    @Test
    void shouldRejectSymlinkedDataRootWhenSupported() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path link = tempDir.resolve("link");
        assumeSymlinkCreated(link, target);

        assertThatThrownBy(() -> validationService.prepareDataRoot(link.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot must not include symbolic links.");
    }

    @Test
    void shouldRejectSymlinkedParentWhenSupported() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("real-parent"));
        Path link = tempDir.resolve("parent-link");
        assumeSymlinkCreated(link, target);

        assertThatThrownBy(() -> validationService.prepareDataRoot(link.resolve("data").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot must not include symbolic links.");
    }

    @Test
    void shouldRejectSymlinkedArtifactDirectoryWhenSupported() throws Exception {
        Path dataRoot = Files.createDirectory(tempDir.resolve("localhive-data"));
        Path target = Files.createDirectory(tempDir.resolve("external-artifacts"));
        Path artifactsLink = dataRoot.resolve("artifacts");
        assumeSymlinkCreated(artifactsLink, target);

        assertThatThrownBy(() -> validationService.prepareDataRoot(dataRoot.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot must not include symbolic links.");
    }

    @Test
    void shouldRejectUnwritableDirectoryWhenPractical() throws Exception {
        Path dataRoot = Files.createDirectory(tempDir.resolve("unwritable"));
        File file = dataRoot.toFile();
        assumeTrue(file.setWritable(false, false));

        try {
            assumeFalse(Files.isWritable(dataRoot));
            assertThatThrownBy(() -> validationService.prepareDataRoot(dataRoot.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("dataRoot directories must be writable.");
        } finally {
            file.setWritable(true, false);
        }
    }

    private static void assumeSymlinkCreated(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available in this test environment.");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
