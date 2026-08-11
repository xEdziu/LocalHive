package dev.adrian.goral.localhivebackend.controller;

import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminResearchFaultScenarioControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m33-admin";
    private static final String CATALOG_PATH = "/api/admin/research/fault-scenarios";
    private static final String VALIDATE_PATH = "/api/admin/research/fault-scenarios/validate";

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
    private WorkerCapabilitiesRepository workerCapabilitiesRepository;

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
        workerCapabilitiesRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(User.builder()
                .username(ADMIN_USERNAME + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    @Test
    void shouldReturnResearchFaultScenarioCatalogForAdmin() throws Exception {
        String response = mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.scenarios").isArray())
                .andExpect(jsonPath("$.scenarios", hasSize(9)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<List<String>>read(response, "$.scenarios[*].id"))
                .containsExactly(
                        "WORKER_OFFLINE_DURING_EXECUTION",
                        "TASK_FAILURE_EXIT_CODE",
                        "MERGE_FAILURE_AGENT",
                        "CANCELLED_GROUP_QUEUED",
                        "INVALID_REST_PAYLOAD",
                        "MALFORMED_SOAP_REQUEST",
                        "BROKEN_WEBSOCKET_STREAM",
                        "UNSUPPORTED_PROTOCOL_COMBINATION",
                        "LONG_RUNNING_TIMEOUT"
                );
        assertSafeFaultScenarioResponse(response);
    }

    @Test
    void shouldReturnKnownResearchFaultScenarioById() throws Exception {
        String response = mockMvc.perform(get(CATALOG_PATH + "/MERGE_FAILURE_AGENT")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("MERGE_FAILURE_AGENT"))
                .andExpect(jsonPath("$.type").value("MERGE_FAILURE"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.injectionMode").value("WORKLOAD_LEVEL"))
                .andExpect(jsonPath("$.expectedSystemBehavior").value("SAFE_FAILURE_STATUS"))
                .andExpect(jsonPath("$.requiresExistingExecutionGroup").value(true))
                .andExpect(jsonPath("$.requiresDocker").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeFaultScenarioResponse(response);
    }

    @Test
    void shouldReturnNotFoundForUnknownResearchFaultScenario() throws Exception {
        mockMvc.perform(get(CATALOG_PATH + "/NOT_A_FAULT")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldValidateSupportedSoapFaultScenario() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "MALFORMED_SOAP_REQUEST",
                          "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                          "protocol": "SOAP",
                          "operation": "GET_GROUP_DETAIL",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value("SUPPORTED"));
    }

    @Test
    void shouldRejectMalformedSoapFaultScenarioWithRestProtocol() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "MALFORMED_SOAP_REQUEST",
                          "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                          "protocol": "REST",
                          "operation": "GET_GROUP_DETAIL",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("FAULT_REQUIRES_SOAP"));
    }

    @Test
    void shouldValidateBrokenWebSocketStreamFaultScenario() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "BROKEN_WEBSOCKET_STREAM",
                          "workloadId": "MANY_SMALL_JOBS_20",
                          "protocol": "WEBSOCKET",
                          "operation": "STREAM_GROUP_ACTIVITY",
                          "dataTransferMode": "STREAMED_EVENTS",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value("SUPPORTED"));
    }

    @Test
    void shouldRejectBrokenWebSocketStreamFaultScenarioWithSoapProtocol() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "BROKEN_WEBSOCKET_STREAM",
                          "workloadId": "MANY_SMALL_JOBS_20",
                          "protocol": "SOAP",
                          "operation": "STREAM_GROUP_ACTIVITY",
                          "dataTransferMode": "STREAMED_EVENTS",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("FAULT_REQUIRES_WEBSOCKET"));
    }

    @Test
    void shouldValidateCancelledQueuedGroupFaultScenario() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "CANCELLED_GROUP_QUEUED",
                          "workloadId": "CANCELLED_GROUP_QUEUED",
                          "protocol": "REST",
                          "operation": "CREATE_EXECUTION_GROUP",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value("SUPPORTED"));
    }

    @Test
    void shouldRejectMergeFailureFaultScenarioForNonMergeWorkload() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "MERGE_FAILURE_AGENT",
                          "workloadId": "SHARDED_OPTIMIZATION_4",
                          "protocol": "REST",
                          "operation": "CREATE_EXECUTION_GROUP",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("FAULT_NOT_COMPATIBLE_WITH_WORKLOAD"));
    }

    @Test
    void shouldAllowUnsupportedProtocolCombinationAsIntentionalFaultScenario() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "UNSUPPORTED_PROTOCOL_COMBINATION",
                          "workloadId": "NO_OP_TINY",
                          "protocol": "SOAP",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value("SUPPORTED"));
    }

    @Test
    void shouldReturnUnknownScenarioAndWorkloadValidationResults() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "scenarioId": "NOT_A_FAULT",
                          "workloadId": "NO_OP_TINY",
                          "protocol": "REST",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("UNKNOWN_FAULT_SCENARIO"));

        mockMvc.perform(validate("""
                        {
                          "scenarioId": "TASK_FAILURE_EXIT_CODE",
                          "workloadId": "NOT_A_WORKLOAD",
                          "protocol": "REST",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("UNKNOWN_WORKLOAD"));
    }

    @Test
    void shouldEnforceAdminSecurityForResearchFaultScenarioEndpoints() throws Exception {
        WorkerCredentials credentials = createWorkerCredentials();

        mockMvc.perform(get(CATALOG_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(VALIDATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedTaskFailureRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(CATALOG_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(VALIDATE_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedTaskFailureRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(CATALOG_PATH)
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(VALIDATE_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedTaskFailureRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(validate(supportedTaskFailureRequest()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldKeepResearchFaultScenarioResponsesSafe() throws Exception {
        String catalog = mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String detail = mockMvc.perform(get(CATALOG_PATH + "/TASK_FAILURE_EXIT_CODE")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String validation = mockMvc.perform(validate(supportedTaskFailureRequest()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeFaultScenarioResponse(catalog);
        assertSafeFaultScenarioResponse(detail);
        assertSafeFaultScenarioResponse(validation);
    }

    @Test
    void shouldNotMutateRuntimeStateWhenReadingOrValidatingResearchFaultScenarios() throws Exception {
        long benchmarkEventCount = benchmarkEventRepository.count();
        long benchmarkMeasurementCount = benchmarkMeasurementRepository.count();
        long benchmarkScenarioCount = benchmarkScenarioRepository.count();
        long benchmarkRunCount = benchmarkRunRepository.count();
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
        long workerCount = workerRepository.count();

        mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(get(CATALOG_PATH + "/WORKER_OFFLINE_DURING_EXECUTION")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(validate(supportedTaskFailureRequest()))
                .andExpect(status().isOk());

        assertThat(benchmarkEventRepository.count()).isEqualTo(benchmarkEventCount);
        assertThat(benchmarkMeasurementRepository.count()).isEqualTo(benchmarkMeasurementCount);
        assertThat(benchmarkScenarioRepository.count()).isEqualTo(benchmarkScenarioCount);
        assertThat(benchmarkRunRepository.count()).isEqualTo(benchmarkRunCount);
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
        assertThat(workerRepository.count()).isEqualTo(workerCount);
    }

    private MockHttpServletRequestBuilder validate(String body) {
        return post(VALIDATE_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String supportedTaskFailureRequest() {
        return """
                {
                  "scenarioId": "TASK_FAILURE_EXIT_CODE",
                  "workloadId": "FAILING_TASK_SINGLE",
                  "protocol": "REST",
                  "operation": "CREATE_SINGLE_EXECUTION",
                  "dataTransferMode": "INLINE_JSON",
                  "payloadFormat": "JSON"
                }
                """;
    }

    private WorkerCredentials createWorkerCredentials() {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m33-worker-" + UUID.randomUUID())
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

    private static void assertSafeFaultScenarioResponse(String response) {
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

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
