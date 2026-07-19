package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.repository.SystemSettingRepository;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.service.storage.StorageConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SetupStorageIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @TempDir
    private Path tempDir;

    @Test
    void shouldCompleteSetupWithDataRootAndKeepPublicStatusSafe() throws Exception {
        Path dataRoot = tempDir.resolve("master-data").toAbsolutePath().normalize();

        mockMvc.perform(post("/api/setup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "Admin_01",
                                  "password": "StrongPassword123!",
                                  "dataRoot": "%s"
                                }
                                """.formatted(jsonString(dataRoot))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(content().string(not(containsString(dataRoot.toString()))));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(systemSettingRepository.findById(StorageConfigurationService.DATA_ROOT_SETTING_KEY))
                .hasValueSatisfying(setting ->
                        assertThat(setting.getConfigValue()).isEqualTo(dataRoot.toString()));
        assertThat(Files.isDirectory(dataRoot)).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("artifacts"))).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("temp"))).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("snapshots"))).isTrue();
        assertThat(Files.isDirectory(dataRoot.resolve("logs"))).isTrue();

        mockMvc.perform(get("/api/setup/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresSetup").value(false))
                .andExpect(content().string(not(containsString("dataRoot"))))
                .andExpect(content().string(not(containsString("artifactsRoot"))))
                .andExpect(content().string(not(containsString(dataRoot.toString()))));
    }

    private static String jsonString(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
