package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.SystemSetting;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.repository.SystemSettingRepository;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.service.storage.StorageConfigurationService;
import dev.adrian.goral.localhivebackend.service.storage.StoragePathValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SystemSettingRepository systemSettingRepository;

    @TempDir
    private Path tempDir;

    private SetupService setupService;

    @BeforeEach
    void setUp() {
        StorageConfigurationService storageConfigurationService = new StorageConfigurationService(
                systemSettingRepository,
                new StoragePathValidationService()
        );
        setupService = new SetupService(userRepository, passwordEncoder, storageConfigurationService);
    }

    @Test
    void shouldCompleteSetupWithoutDataRoot() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("StrongPassword123!")).thenReturn("hashed-password");

        setupService.completeFirstTimeSetup("Admin_01", "StrongPassword123!", null);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("Admin_01");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed-password");
        verifyNoInteractions(systemSettingRepository);
    }

    @Test
    void shouldStoreDataRootAndCreateRequiredSubdirectories() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("StrongPassword123!")).thenReturn("hashed-password");
        when(systemSettingRepository.save(any(SystemSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Path dataRoot = tempDir.resolve("localhive-data");

        setupService.completeFirstTimeSetup("Admin_01", "StrongPassword123!", dataRoot.toString());

        ArgumentCaptor<SystemSetting> settingCaptor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository).save(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getConfigKey()).isEqualTo(StorageConfigurationService.DATA_ROOT_SETTING_KEY);
        assertThat(settingCaptor.getValue().getConfigValue()).isEqualTo(dataRoot.toAbsolutePath().normalize().toString());
        assertThat(Files.isDirectory(dataRoot)).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("artifacts"))).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("temp"))).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("snapshots"))).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("logs"))).isTrue();
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldNotCreateAdminWhenDataRootIsInvalid() throws Exception {
        when(userRepository.count()).thenReturn(0L);
        Path existingFile = Files.createFile(tempDir.resolve("not-a-directory"));

        assertThatThrownBy(() -> setupService.completeFirstTimeSetup(
                "Admin_01",
                "StrongPassword123!",
                existingFile.toString()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataRoot existing path segments must be directories.");

        verify(userRepository, never()).save(any(User.class));
        verify(systemSettingRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void shouldRejectSecondSetupAttempt() {
        when(userRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> setupService.completeFirstTimeSetup("Admin_01", "StrongPassword123!", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("System is already configured. Cannot run setup again.");

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(systemSettingRepository);
    }
}
