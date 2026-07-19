package dev.adrian.goral.localhivebackend.service.storage;

import dev.adrian.goral.localhivebackend.domain.SystemSetting;
import dev.adrian.goral.localhivebackend.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StorageConfigurationService {

    public static final String DATA_ROOT_SETTING_KEY = "storage.dataRoot";

    private final SystemSettingRepository systemSettingRepository;
    private final StoragePathValidationService pathValidationService;

    public Optional<StorageLayout> configureDataRootIfPresent(String dataRoot) {
        if (dataRoot == null) {
            return Optional.empty();
        }

        StorageLayout layout = pathValidationService.prepareDataRoot(dataRoot);
        systemSettingRepository.save(SystemSetting.builder()
                .configKey(DATA_ROOT_SETTING_KEY)
                .configValue(layout.dataRoot().toString())
                .build());
        return Optional.of(layout);
    }

    public Path artifactsRoot(Path fallbackArtifactsRoot) {
        return configuredDataRoot()
                .map(dataRoot -> dataRoot.resolve("artifacts").toAbsolutePath().normalize())
                .orElseGet(() -> fallbackArtifactsRoot.toAbsolutePath().normalize());
    }

    public Optional<Path> configuredDataRoot() {
        return systemSettingRepository.findById(DATA_ROOT_SETTING_KEY)
                .map(SystemSetting::getConfigValue)
                .map(StorageConfigurationService::parseConfiguredDataRoot);
    }

    private static Path parseConfiguredDataRoot(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configured storage dataRoot is blank.");
        }

        try {
            Path path = Path.of(value.trim());
            if (!path.isAbsolute()) {
                throw new IllegalStateException("Configured storage dataRoot must be absolute.");
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Configured storage dataRoot is invalid.", e);
        }
    }
}
