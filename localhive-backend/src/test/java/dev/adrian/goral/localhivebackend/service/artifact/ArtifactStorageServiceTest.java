package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.domain.SystemSetting;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.repository.SystemSettingRepository;
import dev.adrian.goral.localhivebackend.service.storage.StorageConfigurationService;
import dev.adrian.goral.localhivebackend.service.storage.StoragePathValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactStorageServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldStoreWorkspacePackageUnderConfiguredDataRootArtifactsDirectory() throws Exception {
        Path dataRoot = tempDir.resolve("configured-data-root").toAbsolutePath().normalize();
        ArtifactStorageService storageService = storageServiceWithDataRoot(dataRoot);
        UUID artifactId = UUID.randomUUID();

        StoredArtifact storedArtifact = storageService.storeWorkspacePackage(
                artifactId,
                new MockMultipartFile(
                        "file",
                        "package.zip",
                        "application/zip",
                        "workspace".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertThat(storedArtifact.storagePath()).isEqualTo(artifactId + "/package.zip");
        assertThat(storedArtifact.storagePath()).doesNotContain(dataRoot.toString());
        assertThat(Files.readString(dataRoot.resolve("artifacts").resolve(storedArtifact.storagePath())))
                .isEqualTo("workspace");
        assertThat(Files.notExists(tempDir.resolve("fallback-artifacts").resolve(storedArtifact.storagePath())))
                .isTrue();
    }

    @Test
    void shouldStoreExecutionOutputUnderConfiguredDataRootArtifactsDirectory() throws Exception {
        Path dataRoot = tempDir.resolve("configured-output-root").toAbsolutePath().normalize();
        ArtifactStorageService storageService = storageServiceWithDataRoot(dataRoot);
        UUID artifactId = UUID.randomUUID();

        StoredArtifact storedArtifact = storageService.storeExecutionOutput(
                artifactId,
                new MockMultipartFile(
                        "file",
                        "output.txt",
                        "text/plain",
                        "output".getBytes(StandardCharsets.UTF_8)
                ),
                1024
        );

        assertThat(storedArtifact.storagePath()).isEqualTo(artifactId + "/artifact");
        assertThat(storedArtifact.storagePath()).doesNotContain(dataRoot.toString());
        assertThat(Files.readString(dataRoot.resolve("artifacts").resolve(storedArtifact.storagePath())))
                .isEqualTo("output");
    }

    @Test
    void shouldUseFallbackStorageRootWithoutConfiguredDataRoot() throws Exception {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(StorageConfigurationService.DATA_ROOT_SETTING_KEY)).thenReturn(Optional.empty());
        ArtifactStorageService storageService = new ArtifactStorageService(
                new StorageConfigurationService(repository, new StoragePathValidationService()),
                tempDir.resolve("fallback-artifacts").toString()
        );
        UUID artifactId = UUID.randomUUID();

        StoredArtifact storedArtifact = storageService.storeWorkspacePackage(
                artifactId,
                new MockMultipartFile(
                        "file",
                        "package.zip",
                        "application/zip",
                        "default".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertThat(storedArtifact.storagePath()).isEqualTo(artifactId + "/package.zip");
        assertThat(Files.readString(tempDir.resolve("fallback-artifacts").resolve(storedArtifact.storagePath())))
                .isEqualTo("default");
    }

    @Test
    void shouldResolveReadablePathUnderConfiguredDataRootArtifactsDirectory() throws Exception {
        Path dataRoot = tempDir.resolve("configured-download-root").toAbsolutePath().normalize();
        ArtifactStorageService storageService = storageServiceWithDataRoot(dataRoot);
        UUID artifactId = UUID.randomUUID();
        StoredArtifact storedArtifact = storageService.storeExecutionOutput(
                artifactId,
                new MockMultipartFile(
                        "file",
                        "output.txt",
                        "text/plain",
                        "download".getBytes(StandardCharsets.UTF_8)
                ),
                1024
        );
        Artifact artifact = Artifact.create(
                artifactId,
                ArtifactKind.EXECUTION_OUTPUT,
                "output.txt",
                "text/plain",
                storedArtifact.sizeBytes(),
                storedArtifact.sha256(),
                storedArtifact.storagePath(),
                LocalDateTime.now(),
                "worker"
        );

        Path readablePath = storageService.resolveReadablePath(artifact);

        assertThat(readablePath).isEqualTo(dataRoot.resolve("artifacts").resolve(storedArtifact.storagePath()));
        assertThat(Files.readString(readablePath)).isEqualTo("download");
    }

    private ArtifactStorageService storageServiceWithDataRoot(Path dataRoot) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(StorageConfigurationService.DATA_ROOT_SETTING_KEY))
                .thenReturn(Optional.of(SystemSetting.builder()
                        .configKey(StorageConfigurationService.DATA_ROOT_SETTING_KEY)
                        .configValue(dataRoot.toString())
                        .build()));
        return new ArtifactStorageService(
                new StorageConfigurationService(repository, new StoragePathValidationService()),
                tempDir.resolve("fallback-artifacts").toString()
        );
    }
}
