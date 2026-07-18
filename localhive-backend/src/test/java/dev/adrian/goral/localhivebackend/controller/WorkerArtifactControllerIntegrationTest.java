package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactManagementService;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "localhive.artifacts.storage-root=target/test-artifacts/download")
@AutoConfigureMockMvc
class WorkerArtifactControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String EXECUTION_LEASE_HEADER = "X-EXECUTION-LEASE";
    private static final MediaType APPLICATION_ZIP = MediaType.valueOf("application/zip");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ArtifactManagementService artifactManagementService;

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

    @Autowired
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldDownloadReferencedWorkspacePackageWithValidApiKeyAndLease() throws Exception {
        WorkerCredentials worker = createApprovedWorker("artifact-download-success");
        byte[] artifactContent = "workspace download".getBytes(StandardCharsets.UTF_8);
        Artifact artifact = createWorkspaceArtifact(artifactContent, "workspace.zip");
        WorkExecution execution = createAssignedWorkspaceExecution(artifact, worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(get(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/{artifactId}/download",
                        worker.worker().getId(),
                        execution.getId(),
                        artifact.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("workspace.zip")))
                .andExpect(content().contentType(APPLICATION_ZIP))
                .andExpect(content().bytes(artifactContent));
    }

    @Test
    void shouldRejectWorkspacePackageDownloadWithoutApiKeyOrLease() throws Exception {
        WorkerCredentials worker = createApprovedWorker("artifact-download-missing-headers");
        Artifact artifact = createWorkspaceArtifact("workspace".getBytes(StandardCharsets.UTF_8), "workspace.zip");
        WorkExecution execution = createAssignedWorkspaceExecution(artifact, worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(get(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/{artifactId}/download",
                        worker.worker().getId(),
                        execution.getId(),
                        artifact.getId()
                )
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Worker authentication failed."));

        mockMvc.perform(get(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/{artifactId}/download",
                        worker.worker().getId(),
                        execution.getId(),
                        artifact.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required request header is missing: X-EXECUTION-LEASE"));
    }

    @Test
    void shouldRejectWorkspacePackageDownloadWithInvalidLease() throws Exception {
        WorkerCredentials worker = createApprovedWorker("artifact-download-invalid-lease");
        Artifact artifact = createWorkspaceArtifact("workspace".getBytes(StandardCharsets.UTF_8), "workspace.zip");
        WorkExecution execution = createAssignedWorkspaceExecution(artifact, worker.worker());

        mockMvc.perform(get(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/{artifactId}/download",
                        worker.worker().getId(),
                        execution.getId(),
                        artifact.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, "bad-lease-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Execution lease is invalid."));
    }

    @Test
    void shouldRejectWorkspacePackageDownloadForDifferentWorker() throws Exception {
        WorkerCredentials assignedWorker = createApprovedWorker("artifact-download-assigned-worker");
        WorkerCredentials otherWorker = createApprovedWorker("artifact-download-other-worker");
        Artifact artifact = createWorkspaceArtifact("workspace".getBytes(StandardCharsets.UTF_8), "workspace.zip");
        WorkExecution execution = createAssignedWorkspaceExecution(artifact, assignedWorker.worker());
        String leaseToken = claimWithApi(assignedWorker);

        mockMvc.perform(get(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/{artifactId}/download",
                        otherWorker.worker().getId(),
                        execution.getId(),
                        artifact.getId()
                )
                        .header(API_KEY_HEADER, otherWorker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Execution lease is invalid."));
    }

    @Test
    void shouldRejectWorkspacePackageDownloadForArtifactNotReferencedByExecution() throws Exception {
        WorkerCredentials worker = createApprovedWorker("artifact-download-unreferenced");
        Artifact referencedArtifact = createWorkspaceArtifact("workspace".getBytes(StandardCharsets.UTF_8), "workspace.zip");
        Artifact otherArtifact = createWorkspaceArtifact("other workspace".getBytes(StandardCharsets.UTF_8), "other.zip");
        WorkExecution execution = createAssignedWorkspaceExecution(referencedArtifact, worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(get(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/{artifactId}/download",
                        worker.worker().getId(),
                        execution.getId(),
                        otherArtifact.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
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

    private WorkExecution createAssignedWorkspaceExecution(Artifact artifact, Worker worker) {
        WorkDefinitionVersion definitionVersion = createWorkspaceDefinition(artifact);
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                definitionVersion.getId(),
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

    private WorkDefinitionVersion createWorkspaceDefinition(Artifact artifact) {
        User user = createAdminUser("artifact-download-definition-admin");
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive.artifact-download-" + UUID.randomUUID(),
                WorkType.TASK,
                "Docker Workspace Download",
                null,
                "localhive.docker.workload",
                1,
                dockerConfiguration(artifact),
                ResourceRequest.of(128, 1, false),
                user.getId()
        ));
    }

    private Artifact createWorkspaceArtifact(byte[] content, String filename) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "application/zip",
                content
        );
        return artifactManagementService.storeWorkspacePackage(file, "worker-download-test");
    }

    private WorkerCredentials createApprovedWorker(String suffix) {
        createAdminUser(suffix + "-setup");
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

    private static ObjectNode dockerConfiguration(Artifact artifact) {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("image", "alpine:3.20");
        ArrayNode command = configuration.putArray("command");
        command.add("sh");
        command.add("-c");
        command.add("ls -la /workspace");
        configuration.put("timeoutSeconds", 30);
        ObjectNode resources = configuration.putObject("resources");
        resources.put("memoryMb", 128);
        resources.put("cpuCores", 1);
        ObjectNode gpu = configuration.putObject("gpu");
        gpu.put("required", false);
        ObjectNode workspace = configuration.putObject("workspace");
        workspace.put("artifactId", artifact.getId().toString());
        workspace.put("mountPath", "/workspace");
        workspace.put("readOnly", true);
        return configuration;
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
