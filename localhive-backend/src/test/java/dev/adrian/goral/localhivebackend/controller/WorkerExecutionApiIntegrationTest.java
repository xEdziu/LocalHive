package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionClaimService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkerExecutionApiIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String EXECUTION_LEASE_HEADER = "X-EXECUTION-LEASE";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

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
    private WorkExecutionRepository executionRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldClaimRunAndSucceedNoOpExecutionThroughWorkerApi() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-no-op-success");
        WorkDefinitionVersion noOpVersion = noOpVersion();
        WorkExecution execution = createAssignedNoOpExecution(noOpVersion, worker.worker());

        String claimBody = mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        worker.worker().getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("NO-OP smoke test"))
                .andExpect(jsonPath("$.executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.configuration.message").value("noop"))
                .andExpect(jsonPath("$.requiredRamMb").value(0))
                .andExpect(jsonPath("$.requiredCpuCores").value(0))
                .andExpect(jsonPath("$.gpuRequired").value(false))
                .andExpect(jsonPath("$.leaseToken").isNotEmpty())
                .andExpect(jsonPath("$.leaseExpiresAt").exists())
                .andExpect(content().string(not(containsString("leaseTokenHash"))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String leaseToken = JsonPath.read(claimBody, "$.leaseToken");

        assertThat(attemptRepository.existsByExecution(execution)).isFalse();

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/running",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> assertThat(attempt.getStatus())
                        .isEqualTo(ExecutionAttemptStatus.RUNNING));

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/succeeded",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.SUCCEEDED));
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> assertThat(attempt.getStatus())
                        .isEqualTo(ExecutionAttemptStatus.SUCCEEDED));
    }

    @Test
    void shouldReturnDockerDisplayNameInClaimResponse() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-docker-display");
        WorkDefinitionVersion dockerVersion = dockerVersion();
        WorkExecution execution = createAssignedDockerExecution(dockerVersion, worker.worker());

        mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        worker.worker().getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Docker workload: alpine:3.20"))
                .andExpect(jsonPath("$.executorId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.configuration.image").value("alpine:3.20"))
                .andExpect(jsonPath("$.requiredRamMb").value(128))
                .andExpect(jsonPath("$.requiredCpuCores").value(1))
                .andExpect(jsonPath("$.gpuRequired").value(false))
                .andExpect(jsonPath("$.leaseToken").isNotEmpty())
                .andExpect(jsonPath("$.leaseExpiresAt").exists())
                .andExpect(content().string(not(containsString("leaseTokenHash"))));
    }

    @Test
    void shouldReturnNoContentWhenWorkerHasNoAssignedWork() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-no-work");

        mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        worker.worker().getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void shouldRequireApiKeyForClaimAndLeaseHeaderForReports() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-required-headers");
        WorkExecution execution = createAssignedNoOpExecution(noOpVersion(), worker.worker());

        mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        worker.worker().getId()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Worker authentication failed."));

        String leaseToken = claimWithApi(worker);

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/running",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required request header is missing: X-EXECUTION-LEASE"));

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/running",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, "bad-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Execution lease is invalid."));

        assertThat(leaseToken).isNotBlank();
    }

    @Test
    void shouldRejectExpiredLeaseThroughApi() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-expired");
        WorkExecution execution = createAssignedNoOpExecution(noOpVersion(), worker.worker());
        String leaseToken = claimService.claimNextAssignedExecution(
                worker.worker().getId(),
                LocalDateTime.now().minusSeconds(120)
        ).orElseThrow().rawLeaseToken();

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/running",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Execution lease has expired."));
    }

    @Test
    void shouldFailExecutionThroughWorkerApiAndPersistFailureFields() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-failed");
        WorkExecution execution = createAssignedNoOpExecution(noOpVersion(), worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/running",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/failed",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "failureCode", "EXECUTOR_FAILED",
                                "failureMessage", "NO_OP failed intentionally"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.FAILED);
                    assertThat(stored.getFailureCode()).isEqualTo("EXECUTOR_FAILED");
                    assertThat(stored.getFailureMessage()).isEqualTo("NO_OP failed intentionally");
                });
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
                    assertThat(attempt.getFailureCode()).isEqualTo("EXECUTOR_FAILED");
                    assertThat(attempt.getFailureMessage()).isEqualTo("NO_OP failed intentionally");
                });
    }

    @Test
    void shouldRenewLeaseThroughWorkerApiWithoutChangingStatus() throws Exception {
        WorkerCredentials worker = createApprovedWorker("api-renew");
        WorkExecution execution = createAssignedNoOpExecution(noOpVersion(), worker.worker());
        String leaseToken = claimWithApi(worker);

        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/lease/renew",
                        worker.worker().getId(),
                        execution.getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header(EXECUTION_LEASE_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseExpiresAt").exists());

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CLAIMED));
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

    private WorkExecution createAssignedNoOpExecution(WorkDefinitionVersion noOpVersion, Worker worker) {
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                noOpVersion.getId(),
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

    private WorkExecution createAssignedDockerExecution(WorkDefinitionVersion dockerVersion, Worker worker) {
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                dockerVersion.getId(),
                null,
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
        createAdminUser("api-no-op-admin");
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
                        createAdminUser("api-no-op-creator").getId()
                )));
    }

    private WorkDefinitionVersion dockerVersion() {
        createAdminUser("api-docker-admin");
        return definitionRepository.findByLogicalIdentifier("localhive.docker.workload")
                .flatMap(definition -> versionRepository.findByDefinitionAndVersionNumber(definition, 1))
                .orElseGet(() -> definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                        "localhive.docker.workload",
                        WorkType.TASK,
                        "Docker Workload",
                        null,
                        "localhive.docker.workload",
                        1,
                        dockerConfiguration(),
                        ResourceRequest.of(128, 1, false),
                        createAdminUser("api-docker-creator").getId()
                )));
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

    private static ObjectNode noOpConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("message", "noop");
        return configuration;
    }

    private static ObjectNode dockerConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("image", "alpine:3.20");
        configuration.putArray("command").add("sh").add("-c").add("echo LocalHive Docker workload");
        configuration.put("timeoutSeconds", 30);
        ObjectNode resources = configuration.putObject("resources");
        resources.put("memoryMb", 128);
        resources.put("cpuCores", 1);
        ObjectNode gpu = configuration.putObject("gpu");
        gpu.put("required", false);
        return configuration;
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
