package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminSelectionDiagnosticsControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m131-admin";
    private static final String DIAGNOSTICS_PATH = "/api/admin/executions/selection-diagnostics";
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-20T10:00:00");

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
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService creationService;

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
    private WorkerRepository workerRepository;

    @Autowired
    private WorkerCapabilitiesRepository workerCapabilitiesRepository;

    @Autowired
    private UserRepository userRepository;

    private User adminUser;

    @BeforeEach
    void resetDatabase() {
        assignmentRepository.deleteAll();
        executionRepository.deleteAll();
        instanceRepository.deleteAll();
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        workerCapabilitiesRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser(ADMIN_USERNAME);
    }

    @Test
    void shouldRequireAdminSecurityAndRejectWorkerApiKey() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        WorkerCredentials credentials = createWorkerCredentials("security");
        String body = noOpRequest(version.getId(), credentials.worker().getId(), "REQUIRE");

        mockMvc.perform(post(DIAGNOSTICS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(DIAGNOSTICS_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(diagnostics(body)
                        .with(user("operator").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(diagnostics(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentMode").value("REQUIRE"));
    }

    @Test
    void shouldDiagnoseAutoCandidatesAndAvoidSideEffects() throws Exception {
        WorkDefinitionVersion noOpVersion = noOpVersion();
        WorkDefinitionVersion dockerVersion = dockerVersion();
        Worker noCapabilities = createApprovedWorker("no-caps");
        Worker missingExecutor = createApprovedWorker("missing-executor");
        Worker disabledExecutor = createApprovedWorker("disabled-executor");
        Worker dockerDisabled = createApprovedWorker("docker-disabled");
        Worker imageNotAllowed = createApprovedWorker("image-not-allowed");
        Worker policyMemoryTooLow = createApprovedWorker("policy-memory");
        Worker policyCpuTooLow = createApprovedWorker("policy-cpu");
        Worker hardwareMemoryTooLow = createWorker(
                "hardware-memory",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE,
                64,
                8
        );
        Worker hardwareCpuTooLow = createWorker(
                "hardware-cpu",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE,
                4096,
                0
        );
        Worker offline = createWorker(
                "offline",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.OFFLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        Worker paused = createWorker(
                "paused",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.PAUSED
        );
        Worker active = createApprovedWorker("active");
        Worker selected = createApprovedWorker("selected");

        storeNoOpCapabilities(missingExecutor);
        storeDockerCapabilities(disabledExecutor, false, true, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(dockerDisabled, true, false, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(imageNotAllowed, true, true, List.of("ubuntu:24.04"), 4096, 8);
        storeDockerCapabilities(policyMemoryTooLow, true, true, List.of("alpine:3.20"), 127, 8);
        storeDockerCapabilities(policyCpuTooLow, true, true, List.of("alpine:3.20"), 4096, 0);
        storeDockerCapabilities(hardwareMemoryTooLow, true, true, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(hardwareCpuTooLow, true, true, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(offline, true, true, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(paused, true, true, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(active, true, true, List.of("alpine:3.20"), 4096, 8);
        storeDockerCapabilities(selected, true, true, List.of("alpine:3.20"), null, null);
        createAssignedExecution(noOpVersion, active);

        long executionCount = executionRepository.count();
        long instanceCount = instanceRepository.count();
        long assignmentCount = assignmentRepository.count();
        long capabilityCount = workerCapabilitiesRepository.count();

        String response = mockMvc.perform(diagnostics(dockerAutoRequest(dockerVersion.getId(), 128, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logicalId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.assignmentMode").value("AUTO"))
                .andExpect(jsonPath("$.eligibleWorkerCount").value(1))
                .andExpect(jsonPath("$.selectedWorkerId").value(selected.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertWorker(response, selected.getId(), true, true);
        assertReasons(response, noCapabilities.getId(), "MISSING_CAPABILITIES");
        assertReasons(response, missingExecutor.getId(), "EXECUTOR_NOT_SUPPORTED");
        assertReasons(response, disabledExecutor.getId(), "EXECUTOR_DISABLED");
        assertReasons(response, dockerDisabled.getId(), "DOCKER_DISABLED");
        assertReasons(response, imageNotAllowed.getId(), "DOCKER_IMAGE_NOT_ALLOWED");
        assertReasons(response, policyMemoryTooLow.getId(), "DOCKER_POLICY_MEMORY_EXCEEDED");
        assertReasons(response, policyCpuTooLow.getId(), "DOCKER_POLICY_CPU_EXCEEDED");
        assertReasons(response, hardwareMemoryTooLow.getId(), "WORKER_MEMORY_TOO_LOW");
        assertReasons(response, hardwareCpuTooLow.getId(), "WORKER_CPU_TOO_LOW");
        assertReasons(response, offline.getId(), "WORKER_OFFLINE");
        assertReasons(response, paused.getId(), "WORKER_NOT_AVAILABLE");
        assertReasons(response, active.getId(), "WORKER_HAS_ACTIVE_EXECUTION");
        assertThat((Boolean) first(response, noCapabilities.getId(), "capabilities.reported")).isFalse();
        assertThat((Boolean) first(response, selected.getId(), "capabilities.imageAllowed")).isTrue();
        assertSafeDiagnosticsResponse(response);

        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(instanceRepository.count()).isEqualTo(instanceCount);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(workerCapabilitiesRepository.count()).isEqualTo(capabilityCount);
        assertThat(workerRepository.findById(paused.getId()))
                .hasValueSatisfying(worker ->
                        assertThat(worker.getAvailabilityStatus()).isEqualTo(WorkerAvailabilityStatus.PAUSED)
                );
    }

    @Test
    void shouldReturnOkAutoDiagnosticsWhenNoWorkerIsEligible() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        createApprovedWorker("no-auto-caps");

        String response = mockMvc.perform(diagnostics(noOpAutoRequest(version.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleWorkerCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Object selectedWorkerId = JsonPath.read(response, "$.selectedWorkerId");
        assertThat(selectedWorkerId).isNull();
        assertThat(JsonPath.<List<String>>read(response, "$.workers[*].rejectionReasons[*]"))
                .contains("MISSING_CAPABILITIES");
    }

    @Test
    void shouldDiagnosePreferPreferredWorkerAndFallback() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker preferred = createApprovedWorker("prefer-image");
        Worker fallback = createApprovedWorker("prefer-fallback");
        storeDockerCapabilities(preferred, true, true, List.of("ubuntu:24.04"), 4096, 8);
        storeDockerCapabilities(fallback, true, true, List.of("alpine:3.20"), 4096, 8);

        String response = mockMvc.perform(diagnostics(dockerPreferRequest(version.getId(), preferred.getId(), 128, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentMode").value("PREFER"))
                .andExpect(jsonPath("$.eligibleWorkerCount").value(1))
                .andExpect(jsonPath("$.selectedWorkerId").value(fallback.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertReasons(response, preferred.getId(), "DOCKER_IMAGE_NOT_ALLOWED");
        assertWorker(response, fallback.getId(), true, true);
    }

    @Test
    void shouldReturnNullPreferSelectionWhenNoFallbackExistsAndRejectUnknownPreferredWorker() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        Worker preferred = createApprovedWorker("prefer-no-fallback");

        String response = mockMvc.perform(diagnostics(noOpPreferRequest(version.getId(), preferred.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleWorkerCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Object selectedWorkerId = JsonPath.read(response, "$.selectedWorkerId");
        assertThat(selectedWorkerId).isNull();
        assertReasons(response, preferred.getId(), "MISSING_CAPABILITIES");

        mockMvc.perform(diagnostics(noOpPreferRequest(version.getId(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldKeepRequireDiagnosticsIndependentFromCapabilityBlockers() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker worker = createApprovedWorker("require-docker-disabled");
        storeDockerCapabilities(worker, true, false, List.of("ubuntu:24.04"), 1, 1);

        String response = mockMvc.perform(diagnostics(dockerRequireRequest(version.getId(), worker.getId(), 128, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentMode").value("REQUIRE"))
                .andExpect(jsonPath("$.eligibleWorkerCount").value(1))
                .andExpect(jsonPath("$.selectedWorkerId").value(worker.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertWorker(response, worker.getId(), true, true);
        assertThat(reasons(response, worker.getId())).isEmpty();
        assertThat((Boolean) first(response, worker.getId(), "capabilities.reported")).isTrue();
        assertThat((Boolean) first(response, worker.getId(), "capabilities.dockerEnabled")).isFalse();

        Worker pendingWorker = createWorker(
                "require-pending",
                WorkerApprovalStatus.PENDING,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        mockMvc.perform(diagnostics(noOpRequest(noOpVersion().getId(), pendingWorker.getId(), "REQUIRE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidRequestShapeAndInvalidDockerConfiguration() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker worker = createApprovedWorker("validation");

        mockMvc.perform(diagnostics(dockerRequireRequestWithoutWorker(version.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(diagnostics(dockerAutoRequestWithWorker(version.getId(), worker.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(diagnostics(dockerPreferRequestWithoutWorker(version.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(diagnostics(dockerAutoRequest(version.getId(), 15, 1)))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder diagnostics(String body) {
        return post(DIAGNOSTICS_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String noOpRequest(UUID versionId, UUID workerId, String assignmentMode) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "%s",
                  "displayName": "Diagnostic NO_OP",
                  "configuration": {}
                }
                """.formatted(versionId, workerId, assignmentMode);
    }

    private String noOpAutoRequest(UUID versionId) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "assignmentMode": "AUTO",
                  "configuration": {}
                }
                """.formatted(versionId);
    }

    private String noOpPreferRequest(UUID versionId, UUID workerId) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "PREFER",
                  "configuration": {}
                }
                """.formatted(versionId, workerId);
    }

    private String dockerAutoRequest(UUID versionId, int memoryMb, int cpuCores) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "assignmentMode": "AUTO",
                  "configuration": %s
                }
                """.formatted(versionId, dockerConfiguration(memoryMb, cpuCores));
    }

    private String dockerAutoRequestWithWorker(UUID versionId, UUID workerId) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "AUTO",
                  "configuration": %s
                }
                """.formatted(versionId, workerId, dockerConfiguration(128, 1));
    }

    private String dockerPreferRequest(UUID versionId, UUID workerId, int memoryMb, int cpuCores) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "PREFER",
                  "configuration": %s
                }
                """.formatted(versionId, workerId, dockerConfiguration(memoryMb, cpuCores));
    }

    private String dockerPreferRequestWithoutWorker(UUID versionId) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "assignmentMode": "PREFER",
                  "configuration": %s
                }
                """.formatted(versionId, dockerConfiguration(128, 1));
    }

    private String dockerRequireRequest(UUID versionId, UUID workerId, int memoryMb, int cpuCores) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "REQUIRE",
                  "configuration": %s
                }
                """.formatted(versionId, workerId, dockerConfiguration(memoryMb, cpuCores));
    }

    private String dockerRequireRequestWithoutWorker(UUID versionId) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "assignmentMode": "REQUIRE",
                  "configuration": %s
                }
                """.formatted(versionId, dockerConfiguration(128, 1));
    }

    private String dockerConfiguration(int memoryMb, int cpuCores) {
        return """
                {
                  "image": "alpine:3.20",
                  "command": ["sh", "-c", "echo diagnostics"],
                  "timeoutSeconds": 30,
                  "resources": {
                    "memoryMb": %d,
                    "cpuCores": %d
                  },
                  "gpu": {
                    "required": false
                  }
                }
                """.formatted(memoryMb, cpuCores);
    }

    private WorkDefinitionVersion noOpVersion() {
        return createLocalDefinition(
                "localhive.no-op",
                WorkType.TASK,
                "NO_OP",
                "localhive.no-op",
                noOpConfiguration(),
                ResourceRequest.zero()
        );
    }

    private WorkDefinitionVersion dockerVersion() {
        return createLocalDefinition(
                "localhive.docker.workload",
                WorkType.TASK,
                "Docker Workload",
                "localhive.docker.workload",
                dockerBaseConfiguration(),
                ResourceRequest.of(128, 1, false)
        );
    }

    private WorkDefinitionVersion createLocalDefinition(String logicalIdentifier,
                                                        WorkType workType,
                                                        String name,
                                                        String executorId,
                                                        ObjectNode configuration,
                                                        ResourceRequest defaultResourceRequest) {
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                logicalIdentifier,
                workType,
                name,
                null,
                executorId,
                1,
                configuration,
                defaultResourceRequest,
                adminUser.getId()
        ));
    }

    private static ObjectNode noOpConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("message", "noop");
        return configuration;
    }

    private static ObjectNode dockerBaseConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("image", "alpine:3.20");
        configuration.putArray("command")
                .add("sh")
                .add("-c")
                .add("echo LocalHive Docker workload");
        configuration.put("timeoutSeconds", 30);
        configuration.putObject("resources")
                .put("memoryMb", 128)
                .put("cpuCores", 1);
        configuration.putObject("gpu")
                .put("required", false);
        return configuration;
    }

    private WorkExecution createAssignedExecution(WorkDefinitionVersion version, Worker worker) {
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                JsonNodeFactory.instance.objectNode(),
                ResourceRequestOverrides.empty(),
                "Existing active execution"
        ));
        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private Worker createApprovedWorker(String suffix) {
        return createWorker(
                suffix,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
    }

    private WorkerCredentials createWorkerCredentials(String suffix) {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = createApprovedWorker(suffix);
        worker.setApiKeyHash(passwordEncoder.encode(rawApiKey));
        return new WorkerCredentials(workerRepository.save(worker), rawApiKey);
    }

    private Worker createWorker(String suffix,
                                WorkerApprovalStatus approvalStatus,
                                WorkerConnectionStatus connectionStatus,
                                WorkerAvailabilityStatus availabilityStatus) {
        return createWorker(suffix, approvalStatus, connectionStatus, availabilityStatus, 8192, 16);
    }

    private Worker createWorker(String suffix,
                                WorkerApprovalStatus approvalStatus,
                                WorkerConnectionStatus connectionStatus,
                                WorkerAvailabilityStatus availabilityStatus,
                                int sharedRamMb,
                                int cpuCores) {
        return workerRepository.save(Worker.builder()
                .hostname("m131-worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(sharedRamMb)
                .cpuCores(cpuCores)
                .approvalStatus(approvalStatus)
                .connectionStatus(connectionStatus)
                .availabilityStatus(availabilityStatus)
                .build());
    }

    private void storeNoOpCapabilities(Worker worker) {
        storeCapabilities(
                worker,
                executors(executor("localhive.no-op", true)),
                null,
                null,
                null,
                null
        );
    }

    private void storeDockerCapabilities(Worker worker,
                                         boolean executorEnabled,
                                         Boolean dockerEnabled,
                                         List<String> dockerAllowedImages,
                                         Integer dockerMaxMemoryMb,
                                         Integer dockerMaxCpuCores) {
        storeCapabilities(
                worker,
                executors(executor("localhive.docker.workload", executorEnabled)),
                dockerEnabled,
                dockerAllowedImages == null ? null : textArray(dockerAllowedImages),
                dockerMaxMemoryMb,
                dockerMaxCpuCores
        );
    }

    private void storeCapabilities(Worker worker,
                                   ArrayNode executors,
                                   Boolean dockerEnabled,
                                   ArrayNode dockerAllowedImages,
                                   Integer dockerMaxMemoryMb,
                                   Integer dockerMaxCpuCores) {
        WorkerCapabilities capabilities = WorkerCapabilities.create(worker);
        capabilities.replaceWith(
                BASE_TIME,
                executors,
                dockerEnabled,
                dockerAllowedImages,
                dockerMaxMemoryMb,
                dockerMaxCpuCores,
                dockerGpuAllowed(dockerEnabled, dockerAllowedImages, dockerMaxMemoryMb, dockerMaxCpuCores)
        );
        workerCapabilitiesRepository.save(capabilities);
    }

    private static Boolean dockerGpuAllowed(Boolean dockerEnabled,
                                            ArrayNode dockerAllowedImages,
                                            Integer dockerMaxMemoryMb,
                                            Integer dockerMaxCpuCores) {
        if (dockerEnabled == null
                && dockerAllowedImages == null
                && dockerMaxMemoryMb == null
                && dockerMaxCpuCores == null) {
            return null;
        }

        return false;
    }

    private static ObjectNode executor(String executorId, boolean enabled) {
        return JsonNodeFactory.instance.objectNode()
                .put("executorId", executorId)
                .put("executorContractVersion", 1)
                .put("enabled", enabled);
    }

    private static ArrayNode executors(ObjectNode... executors) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (ObjectNode executor : executors) {
            array.add(executor);
        }
        return array;
    }

    private static ArrayNode textArray(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    private static void assertWorker(String response, UUID workerId, boolean eligible, boolean selected) {
        assertThat((Boolean) first(response, workerId, "eligible")).isEqualTo(eligible);
        assertThat((Boolean) first(response, workerId, "selected")).isEqualTo(selected);
    }

    private static void assertReasons(String response, UUID workerId, String reason) {
        assertThat(reasons(response, workerId)).contains(reason);
    }

    private static List<String> reasons(String response, UUID workerId) {
        return JsonPath.read(response, "$.workers[?(@.workerId == '%s')].rejectionReasons[*]".formatted(workerId));
    }

    private static Object first(String response, UUID workerId, String fieldPath) {
        List<Object> values = JsonPath.read(response, "$.workers[?(@.workerId == '%s')].%s".formatted(
                workerId,
                fieldPath
        ));
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private static void assertSafeDiagnosticsResponse(String response) {
        assertThat(response)
                .doesNotContain("apiKey")
                .doesNotContain("apiKeyHash")
                .doesNotContain("passwordHash")
                .doesNotContain("leaseToken")
                .doesNotContain("leaseTokenHash")
                .doesNotContain("leaseExpiresAt")
                .doesNotContain("executorConfiguration")
                .doesNotContain("requestedConfigurationSnapshot")
                .doesNotContain("resolvedConfigurationSnapshot")
                .doesNotContain("configuration")
                .doesNotContain("storagePath")
                .doesNotContain("storageRoot")
                .doesNotContain("dataRoot")
                .doesNotContain("Exception")
                .doesNotContain("StackTrace");
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
