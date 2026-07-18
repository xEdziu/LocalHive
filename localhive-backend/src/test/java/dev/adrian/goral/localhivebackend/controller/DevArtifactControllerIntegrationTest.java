package dev.adrian.goral.localhivebackend.controller;

import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "localhive.artifacts.storage-root=target/test-artifacts/upload")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevArtifactControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String UPLOAD_PATH = "/api/dev/artifacts/workspace-package";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtifactRepository artifactRepository;

    @Test
    void shouldUploadWorkspacePackageWithAdminAndStoreMetadata() throws Exception {
        createUser("artifact-upload-admin");
        byte[] content = "workspace package".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "workspace.zip",
                "application/zip",
                content
        );

        String response = mockMvc.perform(multipart(UPLOAD_PATH)
                        .file(file)
                        .with(user("artifact-upload-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").exists())
                .andExpect(jsonPath("$.kind").value("WORKSPACE_PACKAGE"))
                .andExpect(jsonPath("$.originalFilename").value("workspace.zip"))
                .andExpect(jsonPath("$.contentType").value("application/zip"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.sha256").value(sha256(content)))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(content().string(not(containsString("storagePath"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID artifactId = UUID.fromString(JsonPath.read(response, "$.artifactId"));
        assertThat(artifactRepository.findById(artifactId))
                .hasValueSatisfying(artifact -> {
                    assertThat(artifact.getKind()).isEqualTo(ArtifactKind.WORKSPACE_PACKAGE);
                    assertThat(artifact.getOriginalFilename()).isEqualTo("workspace.zip");
                    assertThat(artifact.getContentType()).isEqualTo("application/zip");
                    assertThat(artifact.getSizeBytes()).isEqualTo(content.length);
                    assertThat(artifact.getSha256()).isEqualTo(sha256(content));
                    assertThat(artifact.getStoragePath()).isEqualTo(artifactId + "/package.zip");
                    assertThat(Files.isRegularFile(Path.of("target/test-artifacts/upload")
                            .resolve(artifact.getStoragePath()))).isTrue();
                });
    }

    @Test
    void shouldRequireAdminForWorkspacePackageUpload() throws Exception {
        createUser("artifact-upload-user");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "workspace.zip",
                "application/zip",
                "workspace package".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart(UPLOAD_PATH)
                        .file(file)
                        .with(user("artifact-upload-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectNonZipWorkspacePackageFilename() throws Exception {
        createUser("artifact-upload-invalid-admin");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "workspace.txt",
                "application/zip",
                "workspace package".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart(UPLOAD_PATH)
                        .file(file)
                        .with(user("artifact-upload-invalid-admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", e);
        }
    }
}
