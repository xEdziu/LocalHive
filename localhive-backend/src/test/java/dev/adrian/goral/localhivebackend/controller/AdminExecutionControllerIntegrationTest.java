package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
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
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import dev.adrian.goral.localhivebackend.service.work.ClaimedExecution;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionClaimService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import dev.adrian.goral.localhivebackend.service.work.WorkerExecutionReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminExecutionControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m7-admin";

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
    private WorkExecutionClaimService claimService;

    @Autowired
    private WorkerExecutionReportService reportService;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private WorkInstanceRepository instanceRepository;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private ExecutionAssignmentRepository assignmentRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private ArtifactRepository artifactRepository;

    @Autowired
    private ExecutionArtifactRepository executionArtifactRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    private User adminUser;

    @BeforeEach
    void resetDatabase() {
        executionArtifactRepository.deleteAll();
        artifactRepository.deleteAll();
        attemptRepository.deleteAll();
        assignmentRepository.deleteAll();
        executionRepository.deleteAll();
        instanceRepository.deleteAll();
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser(ADMIN_USERNAME);
    }

    @Test
    void shouldReturnEmptyExecutionListWhenNoExecutionsExist() throws Exception {
        mockMvc.perform(get("/api/admin/executions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    void shouldListExecutionsNewestFirstWithDisplayNameAndArtifactCount() throws Exception {
        Worker olderWorker = createApprovedWorker("list-newest-old");
        Worker newerWorker = createApprovedWorker("list-newest-new");
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution olderExecution = createAssignedExecution(version, olderWorker, "Older execution");
        addOutputArtifact(olderExecution, olderWorker, "older.txt", "older");
        Thread.sleep(5);
        WorkExecution newerExecution = createAssignedExecution(version, newerWorker, "Newer execution");
        addOutputArtifact(newerExecution, newerWorker, "newer-a.txt", "newer-a");
        addOutputArtifact(newerExecution, newerWorker, "newer-b.txt", "newer-b");

        String response = mockMvc.perform(get("/api/admin/executions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].executionId").value(newerExecution.getId().toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Newer execution"))
                .andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.items[0].executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.items[0].executorContractVersion").value(1))
                .andExpect(jsonPath("$.items[0].workDefinitionLogicalId").value("localhive.no-op"))
                .andExpect(jsonPath("$.items[0].workDefinitionVersion").value(1))
                .andExpect(jsonPath("$.items[0].workerId").value(newerWorker.getId().toString()))
                .andExpect(jsonPath("$.items[0].workerHostname").value(newerWorker.getHostname()))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].assignedAt").exists())
                .andExpect(jsonPath("$.items[0].outputArtifactCount").value(2))
                .andExpect(jsonPath("$.items[1].executionId").value(olderExecution.getId().toString()))
                .andExpect(jsonPath("$.items[1].outputArtifactCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
    }

    @Test
    void shouldApplyLimitAndRejectInvalidLimits() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        createAssignedExecution(version, createApprovedWorker("list-limit-first"), "First execution");
        Thread.sleep(5);
        createAssignedExecution(version, createApprovedWorker("list-limit-second"), "Second execution");

        mockMvc.perform(get("/api/admin/executions")
                        .param("limit", "1")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(2));

        mockMvc.perform(get("/api/admin/executions")
                        .param("limit", "0")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200."));

        mockMvc.perform(get("/api/admin/executions")
                        .param("limit", "201")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200."));

        mockMvc.perform(get("/api/admin/executions")
                        .param("limit", "not-a-number")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be a whole number."));

        mockMvc.perform(get("/api/admin/executions")
                        .param("offset", "-1")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("offset must be greater than or equal to 0."));
    }

    @Test
    void shouldReturnOffsetPageAndEmptyPage() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution oldest = createAssignedExecution(version, createApprovedWorker("page-oldest"), "Page oldest");
        Thread.sleep(5);
        WorkExecution secondOldest = createAssignedExecution(
                version,
                createApprovedWorker("page-second-oldest"),
                "Page second oldest"
        );
        Thread.sleep(5);
        WorkExecution middle = createAssignedExecution(version, createApprovedWorker("page-middle"), "Page middle");
        Thread.sleep(5);
        WorkExecution secondNewest = createAssignedExecution(
                version,
                createApprovedWorker("page-second-newest"),
                "Page second newest"
        );
        Thread.sleep(5);
        WorkExecution newest = createAssignedExecution(version, createApprovedWorker("page-newest"), "Page newest");
        List<WorkExecution> newestFirst = List.of(newest, secondNewest, middle, secondOldest, oldest);

        mockMvc.perform(get("/api/admin/executions")
                        .param("limit", "2")
                        .param("offset", "2")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(2))
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.items[0].executionId").value(newestFirst.get(2).getId().toString()))
                .andExpect(jsonPath("$.items[1].executionId").value(newestFirst.get(3).getId().toString()));

        mockMvc.perform(get("/api/admin/executions")
                        .param("limit", "2")
                        .param("offset", "10")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(10))
                .andExpect(jsonPath("$.totalCount").value(5));
    }

    @Test
    void shouldFilterByStatusAndRejectInvalidStatus() throws Exception {
        Worker worker = createApprovedWorker("list-status");
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution succeededExecution = createAssignedExecution(version, worker, "Succeeded execution");
        runAndSucceed(worker, succeededExecution);
        WorkExecution assignedExecution = createAssignedExecution(version, worker, "Assigned execution");

        mockMvc.perform(get("/api/admin/executions")
                        .param("status", "SUCCEEDED")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].executionId").value(succeededExecution.getId().toString()))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].startedAt").exists())
                .andExpect(jsonPath("$.items[0].completedAt").exists())
                .andExpect(jsonPath("$.items[0].durationMs").value(3000));

        mockMvc.perform(get("/api/admin/executions")
                        .param("status", "NOT_A_STATUS")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown execution status: NOT_A_STATUS"));

        assertThat(executionRepository.findById(assignedExecution.getId()))
                .hasValueSatisfying(execution -> assertThat(execution.getStatus().name()).isEqualTo("ASSIGNED"));
    }

    @Test
    void shouldFilterByWorkerIdAndReturnEmptyForUnknownWorkerId() throws Exception {
        Worker firstWorker = createApprovedWorker("list-worker-a");
        Worker secondWorker = createApprovedWorker("list-worker-b");
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution firstExecution = createAssignedExecution(version, firstWorker, "First worker execution");
        createAssignedExecution(version, secondWorker, "Second worker execution");

        mockMvc.perform(get("/api/admin/executions")
                        .param("workerId", firstWorker.getId().toString())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].executionId").value(firstExecution.getId().toString()))
                .andExpect(jsonPath("$.items[0].workerId").value(firstWorker.getId().toString()))
                .andExpect(jsonPath("$.items[0].workerHostname").value(firstWorker.getHostname()));

        mockMvc.perform(get("/api/admin/executions")
                        .param("workerId", UUID.randomUUID().toString())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCount").value(0));

        mockMvc.perform(get("/api/admin/executions")
                        .param("workerId", "not-a-uuid")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("workerId must be a valid UUID."));
    }

    @Test
    void shouldReturnExecutionDetailWithAssignmentTimingFailureAndArtifactCount() throws Exception {
        Worker worker = createApprovedWorker("detail");
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker, "Detail execution");
        addOutputArtifact(execution, worker, "detail.txt", "detail");
        runAndFail(
                worker,
                execution,
                "DOCKER_WORKLOAD_FAILED",
                "Failed with X-API-KEY=secret and path C:\\secret\\artifact.txt"
        );

        String response = mockMvc.perform(get("/api/admin/executions/{executionId}", execution.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Detail execution"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.workDefinition.logicalId").value("localhive.no-op"))
                .andExpect(jsonPath("$.workDefinition.version").value(1))
                .andExpect(jsonPath("$.workDefinition.name").value("NO_OP"))
                .andExpect(jsonPath("$.assignment.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.assignment.workerHostname").value(worker.getHostname()))
                .andExpect(jsonPath("$.assignment.mode").value("REQUIRE"))
                .andExpect(jsonPath("$.assignment.assignedAt").exists())
                .andExpect(jsonPath("$.assignment.claimedAt").exists())
                .andExpect(jsonPath("$.assignment.leaseExpiresAt").exists())
                .andExpect(jsonPath("$.timing.createdAt").exists())
                .andExpect(jsonPath("$.timing.queuedAt").exists())
                .andExpect(jsonPath("$.timing.assignedAt").exists())
                .andExpect(jsonPath("$.timing.claimedAt").exists())
                .andExpect(jsonPath("$.timing.startedAt").exists())
                .andExpect(jsonPath("$.timing.completedAt").exists())
                .andExpect(jsonPath("$.timing.durationMs").value(3000))
                .andExpect(jsonPath("$.artifacts.outputArtifactCount").value(1))
                .andExpect(jsonPath("$.failure.code").value("DOCKER_WORKLOAD_FAILED"))
                .andExpect(jsonPath("$.failure.message").value(containsString("X-API-KEY=<redacted>")))
                .andExpect(jsonPath("$.failure.message").value(not(containsString("secret"))))
                .andExpect(jsonPath("$.failure.message").value(not(containsString("C:\\secret"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
    }

    @Test
    void shouldReturnNotFoundForMissingExecutionDetail() throws Exception {
        mockMvc.perform(get("/api/admin/executions/{executionId}", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution not found."));
    }

    @Test
    void shouldEnforceAdminSecurityForExecutionEndpoints() throws Exception {
        WorkerCredentials credentials = createApprovedWorkerCredentials("security");
        Worker worker = credentials.worker();
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker, "Security execution");

        mockMvc.perform(get("/api/admin/executions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/executions/{executionId}", execution.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/executions")
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/executions/{executionId}", execution.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/executions")
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/executions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private WorkExecution createAssignedExecution(WorkDefinitionVersion version, Worker worker, String displayName) {
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                JsonNodeFactory.instance.objectNode(),
                null,
                displayName
        ));
        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                LocalDateTime.now()
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private void runAndSucceed(Worker worker, WorkExecution execution) {
        ClaimedExecution claimedExecution = claimService.claimNextAssignedExecution(
                worker.getId(),
                LocalDateTime.parse("2026-07-20T10:00:01")
        ).orElseThrow();
        reportService.reportRunning(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                LocalDateTime.parse("2026-07-20T10:00:02")
        );
        reportService.reportSucceeded(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                LocalDateTime.parse("2026-07-20T10:00:05")
        );
    }

    private void runAndFail(Worker worker, WorkExecution execution, String failureCode, String failureMessage) {
        ClaimedExecution claimedExecution = claimService.claimNextAssignedExecution(
                worker.getId(),
                LocalDateTime.parse("2026-07-20T10:00:01")
        ).orElseThrow();
        reportService.reportRunning(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                LocalDateTime.parse("2026-07-20T10:00:02")
        );
        reportService.reportFailed(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                failureCode,
                failureMessage,
                LocalDateTime.parse("2026-07-20T10:00:05")
        );
    }

    private void addOutputArtifact(WorkExecution execution, Worker worker, String filename, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        UUID artifactId = UUID.randomUUID();
        Artifact artifact = artifactRepository.save(Artifact.create(
                artifactId,
                ArtifactKind.EXECUTION_OUTPUT,
                filename,
                "text/plain",
                bytes.length,
                sha256(bytes),
                artifactId + "/artifact",
                LocalDateTime.now(),
                worker.getId().toString()
        ));
        executionArtifactRepository.save(ExecutionArtifact.create(
                executionRepository.findById(execution.getId()).orElseThrow(),
                artifact,
                workerRepository.findById(worker.getId()).orElseThrow(),
                "results/" + filename,
                LocalDateTime.now()
        ));
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
                        adminUser.getId()
                )));
    }

    private Worker createApprovedWorker(String suffix) {
        return createApprovedWorkerCredentials(suffix).worker();
    }

    private WorkerCredentials createApprovedWorkerCredentials(String suffix) {
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

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static ObjectNode noOpConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("message", "noop");
        return configuration;
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private static void assertSafeAdminResponse(String response) {
        assertThat(response)
                .doesNotContain("leaseToken")
                .doesNotContain("leaseTokenHash")
                .doesNotContain("apiKey")
                .doesNotContain("apiKeyHash")
                .doesNotContain("passwordHash")
                .doesNotContain("storagePath")
                .doesNotContain("storageRoot")
                .doesNotContain("resolvedConfiguration")
                .doesNotContain("C:\\secret");
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", e);
        }
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
