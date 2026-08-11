package dev.adrian.goral.localhivebackend.controller;

import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenario;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkEventRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkMeasurementRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkRunRepository;
import dev.adrian.goral.localhivebackend.repository.research.BenchmarkScenarioRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupMergePlanRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminResearchProtocolComparisonRunnerControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m31-admin";
    private static final String COMPARISON_PATH = "/api/admin/research/protocol-comparison-runs";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BenchmarkEventRepository benchmarkEventRepository;

    @Autowired
    private BenchmarkMeasurementRepository benchmarkMeasurementRepository;

    @Autowired
    private BenchmarkScenarioRepository benchmarkScenarioRepository;

    @Autowired
    private BenchmarkRunRepository benchmarkRunRepository;

    @Autowired
    private ArtifactRepository artifactRepository;

    @Autowired
    private ExecutionArtifactRepository executionArtifactRepository;

    @Autowired
    private ExecutionAssignmentRepository assignmentRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private ExecutionGroupMergePlanRepository mergePlanRepository;

    @Autowired
    private ExecutionGroupRepository groupRepository;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private WorkInstanceRepository instanceRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void resetDatabase() {
        benchmarkEventRepository.deleteAll();
        benchmarkMeasurementRepository.deleteAll();
        benchmarkScenarioRepository.deleteAll();
        benchmarkRunRepository.deleteAll();

        executionArtifactRepository.deleteAll();
        artifactRepository.deleteAll();
        attemptRepository.deleteAll();
        assignmentRepository.deleteAll();
        executionRepository.deleteAll();
        mergePlanRepository.deleteAll();
        groupRepository.deleteAll();
        instanceRepository.deleteAll();
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(User.builder()
                .username(ADMIN_USERNAME + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    @Test
    void shouldCreateProtocolComparisonRunForReadOnlyOperationsAndRecordMeasurements() throws Exception {
        ExecutionGroup targetGroup = createGroup("M31 target group", 4);

        String response = mockMvc.perform(createComparisonRun(validComparisonRequest(targetGroup.getId(), 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.benchmarkRunId").exists())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.targetExecutionGroupId").value(targetGroup.getId().toString()))
                .andExpect(jsonPath("$.workloadId").value("AGENT_MERGE_OPTIMIZATION_4"))
                .andExpect(jsonPath("$.protocols", hasSize(3)))
                .andExpect(jsonPath("$.operations", hasSize(3)))
                .andExpect(jsonPath("$.repetitions").value(2))
                .andExpect(jsonPath("$.scenarioCount").value(18))
                .andExpect(jsonPath("$.measurementCount").value(54))
                .andExpect(jsonPath("$.eventCount").value(112))
                .andExpect(jsonPath("$.summary", hasSize(3)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeProtocolComparisonResponse(response);
        assertThat(JsonPath.<List<String>>read(response, "$.summary[*].protocol"))
                .containsExactly("REST", "WEBSOCKET", "SOAP");
        assertThat(JsonPath.<List<Integer>>read(response, "$.summary[*].completedScenarios"))
                .containsExactly(6, 6, 6);
        assertThat(JsonPath.<List<Integer>>read(response, "$.summary[*].failedScenarios"))
                .containsExactly(0, 0, 0);
        assertThat(JsonPath.<List<Integer>>read(response, "$.summary[*].skippedScenarios"))
                .containsExactly(0, 0, 0);
        assertThat(JsonPath.<List<Integer>>read(response, "$.summary[*].errorCount"))
                .containsExactly(0, 0, 0);
        UUID benchmarkRunId = UUID.fromString(JsonPath.read(response, "$.benchmarkRunId"));

        assertThat(benchmarkRunRepository.findById(benchmarkRunId).orElseThrow().getStatus())
                .isEqualTo(BenchmarkRunStatus.COMPLETED);
        List<BenchmarkScenario> scenarios = benchmarkScenarioRepository.findByBenchmarkRun_IdOrderByScenarioIndexAsc(
                benchmarkRunId
        );
        assertThat(scenarios).hasSize(18);
        assertThat(scenarios)
                .extracting(scenario -> scenario.getProtocol().name())
                .contains("REST", "WEBSOCKET", "SOAP");
        assertThat(scenarios)
                .extracting(scenario -> scenario.getOperation().name())
                .contains("GET_GROUP_DETAIL", "GET_GROUP_ACTIVITY", "GET_GROUP_ARTIFACTS");
        assertThat(benchmarkMeasurementRepository.findAdminMeasurements(
                benchmarkRunId,
                null,
                BenchmarkMeasurementType.REQUEST_LATENCY_MS
        )).hasSize(18);
        assertThat(benchmarkMeasurementRepository.findAdminMeasurements(
                benchmarkRunId,
                null,
                BenchmarkMeasurementType.PAYLOAD_RESPONSE_BYTES
        )).hasSize(18);
        assertThat(benchmarkMeasurementRepository.findAdminMeasurements(
                benchmarkRunId,
                null,
                BenchmarkMeasurementType.ERROR_COUNT
        )).hasSize(18);
        assertThat(benchmarkEventRepository.countByBenchmarkRun_Id(benchmarkRunId)).isEqualTo(112);
    }

    @Test
    void shouldRejectInvalidTargetExecutionGroupWithoutCreatingBenchmarkRun() throws Exception {
        mockMvc.perform(createComparisonRun(validComparisonRequest(UUID.randomUUID(), 1)))
                .andExpect(status().isNotFound());

        assertNoBenchmarkRecords();
    }

    @Test
    void shouldRejectInvalidWorkloadWithoutCreatingBenchmarkRun() throws Exception {
        ExecutionGroup targetGroup = createGroup("Invalid workload target", 4);

        mockMvc.perform(createComparisonRun("""
                        {
                          "displayName": "Invalid workload run",
                          "workloadId": "UNKNOWN_WORKLOAD",
                          "targetExecutionGroupId": "%s",
                          "protocols": ["REST"],
                          "operations": ["GET_GROUP_DETAIL"],
                          "repetitions": 1
                        }
                        """.formatted(targetGroup.getId())))
                .andExpect(status().isBadRequest());

        assertNoBenchmarkRecords();
    }

    @Test
    void shouldRejectUnsupportedOperationsWithoutCreatingBenchmarkRun() throws Exception {
        ExecutionGroup targetGroup = createGroup("Unsupported operation target", 4);

        mockMvc.perform(createComparisonRun("""
                        {
                          "displayName": "Unsupported create operation",
                          "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                          "targetExecutionGroupId": "%s",
                          "protocols": ["REST"],
                          "operations": ["CREATE_EXECUTION_GROUP"],
                          "repetitions": 1
                        }
                        """.formatted(targetGroup.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(createComparisonRun("""
                        {
                          "displayName": "Unsupported stream operation",
                          "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                          "targetExecutionGroupId": "%s",
                          "protocols": ["SOAP"],
                          "operations": ["STREAM_GROUP_ACTIVITY"],
                          "repetitions": 1
                        }
                        """.formatted(targetGroup.getId())))
                .andExpect(status().isBadRequest());

        assertNoBenchmarkRecords();
    }

    @Test
    void shouldEnforceAdminSecurityForProtocolComparisonRunnerEndpoint() throws Exception {
        WorkerCredentials credentials = createWorkerCredentials();
        ExecutionGroup targetGroup = createGroup("Security target group", 4);
        String requestBody = validComparisonRequest(targetGroup.getId(), 1);

        mockMvc.perform(post(COMPARISON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(COMPARISON_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(COMPARISON_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(createComparisonRun(requestBody))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldNotMutateRuntimeStateWhenRunningProtocolComparison() throws Exception {
        ExecutionGroup targetGroup = createGroup("Read-only target group", 4);
        long artifactCount = artifactRepository.count();
        long executionArtifactCount = executionArtifactRepository.count();
        long assignmentCount = assignmentRepository.count();
        long attemptCount = attemptRepository.count();
        long executionCount = executionRepository.count();
        long mergePlanCount = mergePlanRepository.count();
        long groupCount = groupRepository.count();
        long instanceCount = instanceRepository.count();
        long versionCount = versionRepository.count();
        long definitionCount = definitionRepository.count();

        String response = mockMvc.perform(createComparisonRun("""
                        {
                          "displayName": "Read-only mutation guard",
                          "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                          "targetExecutionGroupId": "%s",
                          "protocols": ["REST"],
                          "operations": ["GET_GROUP_DETAIL"],
                          "repetitions": 1
                        }
                        """.formatted(targetGroup.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenarioCount").value(1))
                .andExpect(jsonPath("$.measurementCount").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeProtocolComparisonResponse(response);
        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(executionArtifactRepository.count()).isEqualTo(executionArtifactCount);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(attemptRepository.count()).isEqualTo(attemptCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(mergePlanRepository.count()).isEqualTo(mergePlanCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);
        assertThat(instanceRepository.count()).isEqualTo(instanceCount);
        assertThat(versionRepository.count()).isEqualTo(versionCount);
        assertThat(definitionRepository.count()).isEqualTo(definitionCount);
    }

    private ExecutionGroup createGroup(String displayName, int shardCount) {
        return groupRepository.saveAndFlush(ExecutionGroup.create(
                displayName,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.ALLOW_PARTIAL,
                shardCount,
                LocalDateTime.now()
        ));
    }

    private MockHttpServletRequestBuilder createComparisonRun(String body) {
        return post(COMPARISON_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String validComparisonRequest(UUID targetExecutionGroupId, int repetitions) {
        return """
                {
                  "displayName": "Read model protocol comparison",
                  "description": "Compare REST, WebSocket and SOAP read operations for the same execution group.",
                  "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                  "targetExecutionGroupId": "%s",
                  "protocols": ["REST", "WEBSOCKET", "SOAP"],
                  "operations": ["GET_GROUP_DETAIL", "GET_GROUP_ACTIVITY", "GET_GROUP_ARTIFACTS"],
                  "repetitions": %d,
                  "tags": ["m31", "read-model"],
                  "notes": "M31 runner MVP."
                }
                """.formatted(targetExecutionGroupId, repetitions);
    }

    private WorkerCredentials createWorkerCredentials() {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m31-worker-" + UUID.randomUUID())
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

    private void assertNoBenchmarkRecords() {
        assertThat(benchmarkRunRepository.count()).isZero();
        assertThat(benchmarkScenarioRepository.count()).isZero();
        assertThat(benchmarkMeasurementRepository.count()).isZero();
        assertThat(benchmarkEventRepository.count()).isZero();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private static void assertSafeProtocolComparisonResponse(String response) {
        assertThat(response)
                .doesNotContain("apiKey")
                .doesNotContain("apiKeyHash")
                .doesNotContain("passwordHash")
                .doesNotContain("lease")
                .doesNotContain("token")
                .doesNotContain("secret")
                .doesNotContain("mergePlan")
                .doesNotContain("mergeConfigurationTemplate")
                .doesNotContain("executorConfiguration")
                .doesNotContain("requestedConfigurationSnapshot")
                .doesNotContain("resolvedConfigurationSnapshot")
                .doesNotContain("configuration")
                .doesNotContain("storagePath")
                .doesNotContain("storageRoot")
                .doesNotContain("dataRoot")
                .doesNotContain("target/")
                .doesNotContain("$OUTPUT_DIR")
                .doesNotContain("Exception")
                .doesNotContain("StackTrace");
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
