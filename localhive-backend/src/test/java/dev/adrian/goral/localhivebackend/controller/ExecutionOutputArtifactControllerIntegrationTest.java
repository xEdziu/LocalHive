package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactManagementService;
import dev.adrian.goral.localhivebackend.service.artifact.ExecutionOutputArtifactService;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkerExecutionReportService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "localhive.artifacts.storage-root=target/test-artifacts/output",
        "localhive.artifacts.max-output-size-bytes=16"
})
@AutoConfigureMockMvc
class ExecutionOutputArtifactControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String EXECUTION_LEASE_HEADER = "X-EXECUTION-LEASE";
    private static final String UPLOAD_PATH = "/api/workers/{workerId}/executions/{executionId}/artifacts/output";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

    @Autowired
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkerExecutionReportService reportService;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtifactRepository artifactRepository;

    @Autowired
    private ExecutionArtifactRepository executionArtifactRepository;

    @Autowired
    private ArtifactManagementService artifactManagementService;

    @Test
    void shouldUploadOutputArtifactWithValidApiKeyAndLease() throws Exception {
        createAdminUser("output-upload-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-success");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);
        byte[] content = "output".getBytes(StandardCharsets.UTF_8);

        String response = mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile("output.txt", "text/plain", content))
                        .param("relativePath", "results/output.txt")
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").exists())
                .andExpect(jsonPath("$.kind").value("EXECUTION_OUTPUT"))
                .andExpect(jsonPath("$.executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.relativePath").value("results/output.txt"))
                .andExpect(jsonPath("$.originalFilename").value("output.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.sha256").value(sha256(content)))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(content().string(not(containsString("storagePath"))))
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("lease"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID artifactId = UUID.fromString(JsonPath.read(response, "$.artifactId"));
        assertThat(ExecutionOutputArtifactService.MAX_OUTPUT_ARTIFACT_SIZE_BYTES)
                .isEqualTo(50L * 1024 * 1024);
        assertThat(artifactRepository.findById(artifactId))
                .hasValueSatisfying(artifact -> {
                    assertThat(artifact.getKind()).isEqualTo(ArtifactKind.EXECUTION_OUTPUT);
                    assertThat(artifact.getStoragePath()).isEqualTo(artifactId + "/artifact");
                    assertThat(readAllBytes(Path.of("target/test-artifacts/output")
                            .resolve(artifact.getStoragePath()))).isEqualTo(content);
                });
        assertThat(executionArtifactRepository.findByArtifact_Id(artifactId))
                .hasValueSatisfying(executionArtifact -> {
                    assertThat(executionArtifact.getExecution().getId()).isEqualTo(execution.getId());
                    assertThat(executionArtifact.getUploadedByWorker().getId()).isEqualTo(worker.worker().getId());
                    assertThat(executionArtifact.getRelativePath()).isEqualTo("results/output.txt");
                });
    }

    @Test
    void shouldUploadOutputArtifactForRunningExecution() throws Exception {
        createAdminUser("output-upload-running-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-running");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);
        reportService.reportRunning(worker.worker().getId(), execution.getId(), leaseToken, LocalDateTime.now());

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile("running.txt", "text/plain", "running".getBytes(StandardCharsets.UTF_8)))
                        .param("relativePath", "results/running.txt")
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("EXECUTION_OUTPUT"))
                .andExpect(jsonPath("$.relativePath").value("results/running.txt"));
    }

    @Test
    void shouldFallbackRelativePathToSanitizedOriginalFilename() throws Exception {
        createAdminUser("output-upload-fallback-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-fallback");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);

        String response = mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile("C:\\tmp\\nested\\output.txt", "text/plain", "fallback".getBytes(StandardCharsets.UTF_8)))
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relativePath").value("output.txt"))
                .andExpect(jsonPath("$.originalFilename").value("output.txt"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID artifactId = UUID.fromString(JsonPath.read(response, "$.artifactId"));
        assertThat(artifactRepository.findById(artifactId))
                .hasValueSatisfying(artifact -> assertThat(artifact.getStoragePath()).isEqualTo(artifactId + "/artifact"));
    }

    @Test
    void shouldRejectOutputUploadWithoutOrWithWrongApiKey() throws Exception {
        createAdminUser("output-upload-api-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-api");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Worker authentication failed."));

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile())
                        .header(API_KEY_HEADER, "wrong-api-key")
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Worker authentication failed."));
    }

    @Test
    void shouldRejectOutputUploadWithoutOrWithWrongLease() throws Exception {
        createAdminUser("output-upload-lease-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-lease");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        claimWithApi(worker);

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile())
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required request header is missing: X-EXECUTION-LEASE"));

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile())
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, "wrong-lease"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Execution lease is invalid."));
    }

    @Test
    void shouldRejectOutputUploadForDifferentWorker() throws Exception {
        createAdminUser("output-upload-wrong-worker-admin");
        WorkerCredentials assignedWorker = createApprovedWorker("output-upload-assigned");
        WorkerCredentials otherWorker = createApprovedWorker("output-upload-other");
        WorkExecution execution = createAssignedNoOpExecution(assignedWorker.worker());
        String leaseToken = claimWithApi(assignedWorker);

        mockMvc.perform(multipart(UPLOAD_PATH, otherWorker.worker().getId(), execution.getId())
                        .file(outputFile())
                        .header(API_KEY_HEADER, otherWorker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Execution lease is invalid."));
    }

    @Test
    void shouldRejectOutputUploadForTerminalExecution() throws Exception {
        createAdminUser("output-upload-terminal-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-terminal");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);
        reportService.reportRunning(worker.worker().getId(), execution.getId(), leaseToken, LocalDateTime.now());
        reportService.reportSucceeded(worker.worker().getId(), execution.getId(), leaseToken, LocalDateTime.now());

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile())
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Execution status does not allow this operation: SUCCEEDED."));
    }

    @Test
    void shouldRejectMissingFileAndOversizedFile() throws Exception {
        createAdminUser("output-upload-file-admin");
        WorkerCredentials worker = createApprovedWorker("output-upload-file");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("file is required."));

        mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile("large.txt", "text/plain", "0123456789abcdefg".getBytes(StandardCharsets.UTF_8)))
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("file must be at most 50 MB."));
    }

    @Test
    void shouldRejectInvalidRelativePaths() throws Exception {
        createAdminUser("output-upload-relative-path-admin");
        String[] invalidPaths = {
                "../evil.txt",
                "/absolute.txt",
                "C:\\evil.txt",
                "dir\\..\\evil.txt",
                "   ",
                "bad\u0000name.txt"
        };

        for (String invalidPath : invalidPaths) {
            WorkerCredentials worker = createApprovedWorker("output-upload-invalid-path");
            WorkExecution execution = createAssignedNoOpExecution(worker.worker());
            String leaseToken = claimWithApi(worker);

            mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                            .file(outputFile())
                            .param("relativePath", invalidPath)
                            .header(API_KEY_HEADER, worker.rawApiKey())
                            .header(EXECUTION_LEASE_HEADER, leaseToken))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void shouldListAndDownloadOutputArtifactForAdmin() throws Exception {
        createAdminUser("output-admin-endpoints");
        WorkerCredentials worker = createApprovedWorker("output-admin-endpoints");
        WorkExecution execution = createAssignedNoOpExecution(worker.worker());
        String leaseToken = claimWithApi(worker);
        byte[] content = "result".getBytes(StandardCharsets.UTF_8);

        String uploadResponse = mockMvc.perform(multipart(UPLOAD_PATH, worker.worker().getId(), execution.getId())
                        .file(outputFile("result.txt", "text/plain", content))
                        .param("relativePath", "results/result.txt")
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID artifactId = UUID.fromString(JsonPath.read(uploadResponse, "$.artifactId"));

        mockMvc.perform(get("/api/admin/executions/{executionId}/artifacts", execution.getId())
                        .with(user("output-admin-endpoints").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifactId").value(artifactId.toString()))
                .andExpect(jsonPath("$[0].kind").value("EXECUTION_OUTPUT"))
                .andExpect(jsonPath("$[0].executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$[0].uploadedByWorkerId").value(worker.worker().getId().toString()))
                .andExpect(jsonPath("$[0].relativePath").value("results/result.txt"))
                .andExpect(jsonPath("$[0].originalFilename").value("result.txt"))
                .andExpect(jsonPath("$[0].contentType").value("text/plain"))
                .andExpect(jsonPath("$[0].sizeBytes").value(content.length))
                .andExpect(jsonPath("$[0].sha256").value(sha256(content)))
                .andExpect(content().string(not(containsString("storagePath"))));

        mockMvc.perform(get("/api/admin/artifacts/{artifactId}/download", artifactId)
                        .with(user("output-admin-endpoints").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("result.txt")))
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes(content));
    }

    @Test
    void shouldRequireAdminForOutputArtifactAdminEndpoints() throws Exception {
        createAdminUser("output-admin-auth");

        mockMvc.perform(get("/api/admin/executions/{executionId}/artifacts", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/artifacts/{artifactId}/download", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectWorkspacePackageFromAdminOutputDownloadEndpoint() throws Exception {
        createAdminUser("output-admin-kind");
        Artifact workspaceArtifact = artifactManagementService.storeWorkspacePackage(
                new MockMultipartFile(
                        "file",
                        "workspace.zip",
                        "application/zip",
                        "workspace".getBytes(StandardCharsets.UTF_8)
                ),
                "output-admin-kind"
        );

        mockMvc.perform(get("/api/admin/artifacts/{artifactId}/download", workspaceArtifact.getId())
                        .with(user("output-admin-kind").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Artifact not found."));
    }

    private String claimWithApi(WorkerCredentials worker) throws Exception {
        String body = mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        worker.worker().getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.leaseToken");
    }

    private WorkExecution createAssignedNoOpExecution(Worker worker) {
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                noOpVersion().getId(),
                JsonNodeFactory.instance.objectNode(),
                null
        ));
        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                LocalDateTime.now()
        );
        return execution;
    }

    private WorkDefinitionVersion noOpVersion() {
        return definitionRepository.findByLogicalIdentifier("localhive.no-op")
                .flatMap(definition -> versionRepository.findByDefinitionAndVersionNumber(definition, 1))
                .orElseGet(() -> definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                        "localhive.no-op",
                        WorkType.TASK,
                        "NO_OP",
                        null,
                        "localhive.no-op",
                        1,
                        noOpConfiguration(),
                        ResourceRequest.zero(),
                        createAdminUser("output-no-op-creator").getId()
                )));
    }

    private WorkerCredentials createApprovedWorker(String suffix) {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .apiKeyHash(passwordEncoder.encode(rawApiKey))
                .build());
        return new WorkerCredentials(worker, rawApiKey);
    }

    private User createAdminUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static MockMultipartFile outputFile() {
        return outputFile("output.txt", "text/plain", "output".getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile outputFile(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private static ObjectNode noOpConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("message", "noop");
        return configuration;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", e);
        }
    }

    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
