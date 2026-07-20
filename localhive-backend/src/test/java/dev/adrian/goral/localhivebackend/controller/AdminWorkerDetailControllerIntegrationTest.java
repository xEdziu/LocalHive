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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminWorkerDetailControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m8-admin";
    private static final String INTERNAL_STORAGE_MARKER = "E:\\LocalHive\\internal-artifacts";

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
    void shouldReturnWorkerDetailWithCurrentLastRecentExecutionsAndArtifactCounts() throws Exception {
        WorkerCredentials credentials = createApprovedWorkerCredentials(
                "detail",
                LocalDateTime.parse("2026-07-20T10:00:00")
        );
        Worker worker = credentials.worker();
        WorkDefinitionVersion version = noOpVersion();
        List<WorkExecution> completedExecutions = new ArrayList<>();

        for (int index = 1; index <= 6; index++) {
            WorkExecution execution = createAssignedExecution(
                    version,
                    worker,
                    "Completed execution " + index
            );
            completedExecutions.add(runAndSucceed(
                    worker,
                    execution,
                    LocalDateTime.parse("2026-07-20T09:00:00").plusMinutes(index)
            ));
            Thread.sleep(5);
        }
        WorkExecution latestCompletedExecution = completedExecutions.get(5);
        addOutputArtifact(latestCompletedExecution, worker, "latest-a.txt", "latest-a");
        addOutputArtifact(latestCompletedExecution, worker, "latest-b.txt", "latest-b");

        WorkExecution activeExecution = createAssignedExecution(version, worker, "Running execution");
        WorkExecution runningExecution = runAndKeepRunning(
                worker,
                activeExecution,
                LocalDateTime.parse("2026-07-20T10:00:02")
        );

        String response = mockMvc.perform(get("/api/admin/workers/{workerId}", worker.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.hostname").value(worker.getHostname()))
                .andExpect(jsonPath("$.ipAddress").value("192.168.1.10"))
                .andExpect(jsonPath("$.osType").value("Linux"))
                .andExpect(jsonPath("$.hardware.totalRamMb").value(32768))
                .andExpect(jsonPath("$.hardware.sharedRamMb").value(8192))
                .andExpect(jsonPath("$.hardware.cpuCores").value(16))
                .andExpect(jsonPath("$.hardware.gpuName").value("RTX 5080"))
                .andExpect(jsonPath("$.status.approval").value("APPROVED"))
                .andExpect(jsonPath("$.status.connection").value("ONLINE"))
                .andExpect(jsonPath("$.status.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.heartbeat.lastSeenAt").value("2026-07-20T10:00:00"))
                .andExpect(jsonPath("$.heartbeat.lastHeartbeatAt").value("2026-07-20T10:00:00"))
                .andExpect(jsonPath("$.heartbeat.pauseEnabled").value(false))
                .andExpect(jsonPath("$.currentExecution.executionId").value(runningExecution.getId().toString()))
                .andExpect(jsonPath("$.currentExecution.displayName").value("Running execution"))
                .andExpect(jsonPath("$.currentExecution.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentExecution.executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.currentExecution.executorContractVersion").value(1))
                .andExpect(jsonPath("$.currentExecution.startedAt").value("2026-07-20T10:00:02"))
                .andExpect(jsonPath("$.currentExecution.durationMs").value(nullValue()))
                .andExpect(jsonPath("$.currentExecution.outputArtifactCount").value(0))
                .andExpect(jsonPath("$.lastExecution.executionId").value(runningExecution.getId().toString()))
                .andExpect(jsonPath("$.lastExecution.status").value("RUNNING"))
                .andExpect(jsonPath("$.lastExecution.durationMs").value(nullValue()))
                .andExpect(jsonPath("$.recentExecutions", hasSize(5)))
                .andExpect(jsonPath("$.recentExecutions[0].executionId").value(runningExecution.getId().toString()))
                .andExpect(jsonPath("$.recentExecutions[1].executionId").value(latestCompletedExecution.getId().toString()))
                .andExpect(jsonPath("$.recentExecutions[1].displayName").value("Completed execution 6"))
                .andExpect(jsonPath("$.recentExecutions[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.recentExecutions[1].startedAt").value("2026-07-20T09:06:00"))
                .andExpect(jsonPath("$.recentExecutions[1].completedAt").value("2026-07-20T09:06:03"))
                .andExpect(jsonPath("$.recentExecutions[1].durationMs").value(3000))
                .andExpect(jsonPath("$.recentExecutions[1].outputArtifactCount").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain(completedExecutions.get(0).getId().toString())
                .doesNotContain(completedExecutions.get(1).getId().toString());
        assertSafeAdminWorkerDetailResponse(response, credentials.rawApiKey());
    }

    @Test
    void shouldReturnCompletedLastExecutionWhenWorkerHasNoActiveExecution() throws Exception {
        WorkerCredentials credentials = createApprovedWorkerCredentials(
                "completed-only",
                LocalDateTime.parse("2026-07-20T10:15:00")
        );
        Worker worker = credentials.worker();
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution execution = createAssignedExecution(version, worker, "Completed-only execution");
        WorkExecution completedExecution = runAndSucceed(
                worker,
                execution,
                LocalDateTime.parse("2026-07-20T10:16:00")
        );
        addOutputArtifact(completedExecution, worker, "completed.txt", "completed");

        String response = mockMvc.perform(get("/api/admin/workers/{workerId}", worker.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentExecution").value(nullValue()))
                .andExpect(jsonPath("$.lastExecution.executionId").value(completedExecution.getId().toString()))
                .andExpect(jsonPath("$.lastExecution.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.lastExecution.displayName").value("Completed-only execution"))
                .andExpect(jsonPath("$.lastExecution.outputArtifactCount").value(1))
                .andExpect(jsonPath("$.recentExecutions", hasSize(1)))
                .andExpect(jsonPath("$.recentExecutions[0].executionId").value(completedExecution.getId().toString()))
                .andExpect(jsonPath("$.recentExecutions[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.recentExecutions[0].displayName").value("Completed-only execution"))
                .andExpect(jsonPath("$.recentExecutions[0].outputArtifactCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminWorkerDetailResponse(response, credentials.rawApiKey());
    }

    @Test
    void shouldReturnNullExecutionSectionsForWorkerWithoutExecutions() throws Exception {
        Worker worker = createApprovedWorkerCredentials(
                "empty",
                LocalDateTime.parse("2026-07-20T10:30:00")
        ).worker();

        String response = mockMvc.perform(get("/api/admin/workers/{workerId}", worker.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.currentExecution").value(nullValue()))
                .andExpect(jsonPath("$.lastExecution").value(nullValue()))
                .andExpect(jsonPath("$.recentExecutions", hasSize(0)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminWorkerDetailResponse(response, "unused-api-key");
    }

    @Test
    void shouldReturnNotFoundAndBadRequestForMissingOrInvalidWorkerDetail() throws Exception {
        mockMvc.perform(get("/api/admin/workers/{workerId}", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Worker not found."));

        mockMvc.perform(get("/api/admin/workers/{workerId}", "not-a-uuid")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldEnforceAdminSecurityForWorkerDetailEndpoint() throws Exception {
        WorkerCredentials credentials = createApprovedWorkerCredentials(
                "security",
                LocalDateTime.parse("2026-07-20T11:00:00")
        );

        mockMvc.perform(get("/api/admin/workers/{workerId}", credentials.worker().getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/workers/{workerId}", credentials.worker().getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/workers/{workerId}", credentials.worker().getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/workers/{workerId}", credentials.worker().getId())
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

    private WorkExecution runAndSucceed(Worker worker, WorkExecution execution, LocalDateTime startedAt) {
        ClaimedExecution claimedExecution = claimService.claimNextAssignedExecution(
                worker.getId(),
                startedAt.minusSeconds(1)
        ).orElseThrow();
        reportService.reportRunning(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                startedAt
        );
        reportService.reportSucceeded(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                startedAt.plusSeconds(3)
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private WorkExecution runAndKeepRunning(Worker worker, WorkExecution execution, LocalDateTime startedAt) {
        ClaimedExecution claimedExecution = claimService.claimNextAssignedExecution(
                worker.getId(),
                startedAt.minusSeconds(1)
        ).orElseThrow();
        reportService.reportRunning(
                worker.getId(),
                execution.getId(),
                claimedExecution.rawLeaseToken(),
                startedAt
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
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
                INTERNAL_STORAGE_MARKER + "\\" + artifactId + "\\artifact",
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

    private WorkerCredentials createApprovedWorkerCredentials(String suffix, LocalDateTime lastHeartbeatAt) {
        String rawApiKey = "worker-api-key-secret-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .lastHeartbeatAt(lastHeartbeatAt)
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
        configuration.put("secretConfig", "do-not-expose-config");
        return configuration;
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private static void assertSafeAdminWorkerDetailResponse(String response, String rawApiKey) {
        assertThat(response)
                .doesNotContain(rawApiKey)
                .doesNotContain("apiKey")
                .doesNotContain("apiKeyHash")
                .doesNotContain("passwordHash")
                .doesNotContain("leaseToken")
                .doesNotContain("leaseTokenHash")
                .doesNotContain("leaseExpiresAt")
                .doesNotContain("resolvedConfigurationSnapshot")
                .doesNotContain("requestedConfigurationSnapshot")
                .doesNotContain("do-not-expose-config")
                .doesNotContain("storagePath")
                .doesNotContain("dataRoot")
                .doesNotContain(INTERNAL_STORAGE_MARKER)
                .doesNotContain("internal-artifacts")
                .doesNotContain("Exception")
                .doesNotContain("StackTrace");
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
