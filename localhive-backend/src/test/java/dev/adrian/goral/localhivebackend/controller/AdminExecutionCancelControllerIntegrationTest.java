package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
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
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.AgentCommandRepository;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionCancelService;
import dev.adrian.goral.localhivebackend.service.work.ClaimedExecution;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionClaimService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionLifecycleService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminExecutionCancelControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m14-admin";
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-21T10:00:00");

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
    private WorkExecutionLifecycleService lifecycleService;

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
    private WorkerCapabilitiesRepository workerCapabilitiesRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private AgentCommandRepository agentCommandRepository;

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
        workerCapabilitiesRepository.deleteAll();
        agentCommandRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser(ADMIN_USERNAME);
    }

    @Test
    void shouldEnforceAdminSecurityForCancelEndpoint() throws Exception {
        WorkerCredentials credentials = createApprovedWorkerCredentials("security");
        WorkExecution execution = createAssignedExecution(noOpVersion(), credentials.worker(), "Cancel security");

        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", execution.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", execution.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", execution.getId())
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", execution.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void shouldCancelAssignedExecutionAndExposeSafeDetail() throws Exception {
        Worker worker = createApprovedWorkerCredentials("valid").worker();
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker, "Cancel valid");
        storeCapabilities(worker);
        addOutputArtifact(execution, worker, "cancel.txt", "cancel");
        long assignmentCount = assignmentRepository.count();
        long artifactCount = artifactRepository.count();
        long executionArtifactCount = executionArtifactRepository.count();
        long capabilityCount = workerCapabilitiesRepository.count();
        long agentCommandCount = agentCommandRepository.count();

        String response = mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", execution.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "  Admin requested cancellation  "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Cancel valid"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.assignment.assignmentId").exists())
                .andExpect(jsonPath("$.assignment.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.assignment.workerHostname").value(worker.getHostname()))
                .andExpect(jsonPath("$.assignment.mode").value("REQUIRE"))
                .andExpect(jsonPath("$.assignment.assignedAt").exists())
                .andExpect(jsonPath("$.timing.completedAt").exists())
                .andExpect(jsonPath("$.timing.cancelledAt").exists())
                .andExpect(jsonPath("$.artifacts.outputArtifactCount").value(1))
                .andExpect(jsonPath("$.failure.code").value(AdminExecutionCancelService.ADMIN_CANCELLED_FAILURE_CODE))
                .andExpect(jsonPath("$.failure.message").value("Admin requested cancellation"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED);
                    assertThat(stored.getCompletedAt()).isNotNull();
                    assertThat(stored.getCancelledAt()).isNotNull();
                    assertThat(stored.getFailureCode()).isEqualTo(AdminExecutionCancelService.ADMIN_CANCELLED_FAILURE_CODE);
                    assertThat(stored.getFailureMessage()).isEqualTo("Admin requested cancellation");
                });
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(executionArtifactRepository.count()).isEqualTo(executionArtifactCount);
        assertThat(workerCapabilitiesRepository.count()).isEqualTo(capabilityCount);
        assertThat(agentCommandRepository.count()).isEqualTo(agentCommandCount);
        assertThat(workerRepository.findById(worker.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getApprovalStatus()).isEqualTo(WorkerApprovalStatus.APPROVED);
                    assertThat(stored.getConnectionStatus()).isEqualTo(WorkerConnectionStatus.ONLINE);
                    assertThat(stored.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.AVAILABLE);
                });
        assertThat(workerCapabilitiesRepository.findById(worker.getId()))
                .hasValueSatisfying(capabilities ->
                        assertThat(capabilities.getReportedAt()).isEqualTo(BASE_TIME)
                );

        mockMvc.perform(get("/api/admin/executions/{executionId}", execution.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failure.code").value(AdminExecutionCancelService.ADMIN_CANCELLED_FAILURE_CODE))
                .andExpect(jsonPath("$.failure.message").value("Admin requested cancellation"));
    }

    @Test
    void shouldRejectUnknownAndNonCancellableExecutionStatuses() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", UUID.randomUUID())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution not found."));

        assertCancelConflict(claimedExecution(), "CLAIMED");
        assertCancelConflict(runningExecution(), "RUNNING");
        assertCancelConflict(succeededExecution(), "SUCCEEDED");
        assertCancelConflict(failedExecution(), "FAILED");
        assertCancelConflict(expiredExecution(), "EXPIRED");

        WorkExecution cancelled = createAssignedExecution(
                noOpVersion(),
                createApprovedWorkerCredentials("already-cancelled").worker(),
                "Already cancelled"
        );
        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", cancelled.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertCancelConflict(cancelled, "CANCELLED");
    }

    @Test
    void shouldNotClaimCancelledExecutionAndAllowLaterAssignedExecution() throws Exception {
        WorkerCredentials worker = createApprovedWorkerCredentials("claim-skip");
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution cancelled = createAssignedExecution(version, worker.worker(), "Cancelled before claim");

        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", cancelled.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/workers/{workerId}/assigned-executions/claim-next", worker.worker().getId())
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        WorkExecution next = createAssignedExecution(version, worker.worker(), "Next claimable execution");

        mockMvc.perform(post("/api/workers/{workerId}/assigned-executions/claim-next", worker.worker().getId())
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(next.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Next claimable execution"));

        assertThat(executionRepository.findById(cancelled.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));
        assertThat(executionRepository.findById(next.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CLAIMED));
    }

    @Test
    void shouldHandleOptionalBlankAndTooLongReasons() throws Exception {
        Worker worker = createApprovedWorkerCredentials("reason").worker();
        WorkDefinitionVersion version = noOpVersion();
        WorkExecution noBody = createAssignedExecution(version, worker, "No body cancellation");

        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", noBody.getId())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failure.message")
                        .value(AdminExecutionCancelService.DEFAULT_CANCELLATION_MESSAGE));

        WorkExecution blankReason = createAssignedExecution(version, worker, "Blank reason cancellation");
        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", blankReason.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "   "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failure.message")
                        .value(AdminExecutionCancelService.DEFAULT_CANCELLATION_MESSAGE));

        WorkExecution tooLongReason = createAssignedExecution(version, worker, "Too long reason cancellation");
        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", tooLongReason.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "%s"
                                }
                                """.formatted("x".repeat(AdminExecutionCancelService.MAX_REASON_LENGTH + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "reason must be less than or equal to "
                                + AdminExecutionCancelService.MAX_REASON_LENGTH
                                + " characters."
                ))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("StackTrace"))));
    }

    private void assertCancelConflict(WorkExecution execution, String statusName) throws Exception {
        mockMvc.perform(post("/api/admin/executions/{executionId}/cancel", execution.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot cancel execution from status " + statusName + "."));
    }

    private WorkExecution claimedExecution() {
        WorkerCredentials worker = createApprovedWorkerCredentials("claimed");
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker.worker(), "Claimed execution");
        claimService.claimNextAssignedExecution(worker.worker().getId(), BASE_TIME.plusSeconds(1)).orElseThrow();
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private WorkExecution runningExecution() {
        WorkerCredentials worker = createApprovedWorkerCredentials("running");
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker.worker(), "Running execution");
        ClaimedExecution claimed = claimService.claimNextAssignedExecution(
                worker.worker().getId(),
                BASE_TIME.plusSeconds(1)
        ).orElseThrow();
        reportService.reportRunning(
                worker.worker().getId(),
                execution.getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(2)
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private WorkExecution succeededExecution() {
        WorkerCredentials worker = createApprovedWorkerCredentials("succeeded");
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker.worker(), "Succeeded execution");
        ClaimedExecution claimed = claimService.claimNextAssignedExecution(
                worker.worker().getId(),
                BASE_TIME.plusSeconds(1)
        ).orElseThrow();
        reportService.reportRunning(
                worker.worker().getId(),
                execution.getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(2)
        );
        reportService.reportSucceeded(
                worker.worker().getId(),
                execution.getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(3)
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private WorkExecution failedExecution() {
        WorkerCredentials worker = createApprovedWorkerCredentials("failed");
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker.worker(), "Failed execution");
        ClaimedExecution claimed = claimService.claimNextAssignedExecution(
                worker.worker().getId(),
                BASE_TIME.plusSeconds(1)
        ).orElseThrow();
        reportService.reportRunning(
                worker.worker().getId(),
                execution.getId(),
                claimed.rawLeaseToken(),
                BASE_TIME.plusSeconds(2)
        );
        reportService.reportFailed(
                worker.worker().getId(),
                execution.getId(),
                claimed.rawLeaseToken(),
                "EXECUTOR_FAILED",
                "Failed before cancel request",
                BASE_TIME.plusSeconds(3)
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private WorkExecution expiredExecution() {
        Worker worker = createApprovedWorkerCredentials("expired").worker();
        WorkExecution execution = createAssignedExecution(noOpVersion(), worker, "Expired execution");
        lifecycleService.expire(execution.getId(), BASE_TIME.plusSeconds(1));
        return executionRepository.findById(execution.getId()).orElseThrow();
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

    private void storeCapabilities(Worker worker) {
        ObjectNode executor = JsonNodeFactory.instance.objectNode()
                .put("executorId", "localhive.no-op")
                .put("executorContractVersion", 1)
                .put("enabled", true);
        WorkerCapabilities capabilities = WorkerCapabilities.create(worker);
        capabilities.replaceWith(
                BASE_TIME,
                JsonNodeFactory.instance.arrayNode().add(executor),
                null,
                null,
                null,
                null,
                null
        );
        workerCapabilitiesRepository.save(capabilities);
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
                .doesNotContain("leaseExpiresAt")
                .doesNotContain("apiKey")
                .doesNotContain("apiKeyHash")
                .doesNotContain("passwordHash")
                .doesNotContain("storagePath")
                .doesNotContain("storageRoot")
                .doesNotContain("dataRoot")
                .doesNotContain("resolvedConfiguration")
                .doesNotContain("requestedConfiguration")
                .doesNotContain("executorConfiguration")
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
