package dev.adrian.goral.localhivebackend.controller;

import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminResearchWorkloadCatalogControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m29-admin";
    private static final String CATALOG_PATH = "/api/admin/research/workload-catalog";
    private static final String VALIDATE_PATH = "/api/admin/research/workload-catalog/validate";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    void shouldReturnResearchWorkloadCatalogForAdmin() throws Exception {
        String response = mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.workloads").isArray())
                .andExpect(jsonPath("$.workloads", hasSize(10)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<List<String>>read(response, "$.workloads[*].id"))
                .contains(
                        "NO_OP_TINY",
                        "SMALL_JSON_ECHO",
                        "FILE_IO_SMALL",
                        "SHARDED_OPTIMIZATION_4",
                        "AGENT_MERGE_OPTIMIZATION_4",
                        "LONG_RUNNING_SINGLE",
                        "FAILING_TASK_SINGLE",
                        "CANCELLED_GROUP_QUEUED",
                        "MANY_SMALL_JOBS_20",
                        "FEW_HEAVY_JOBS_3"
                );
        assertSafeResearchWorkloadResponse(response);
    }

    @Test
    void shouldReturnResearchWorkloadById() throws Exception {
        String response = mockMvc.perform(get(CATALOG_PATH + "/AGENT_MERGE_OPTIMIZATION_4")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("AGENT_MERGE_OPTIMIZATION_4"))
                .andExpect(jsonPath("$.type").value("AGENT_MERGE"))
                .andExpect(jsonPath("$.executionShape").value("EXECUTION_GROUP_WITH_AGENT_MERGE"))
                .andExpect(jsonPath("$.requiresMerge").value(true))
                .andExpect(jsonPath("$.suggestedShardCount").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeResearchWorkloadResponse(response);
    }

    @Test
    void shouldReturnNotFoundForUnknownResearchWorkload() throws Exception {
        mockMvc.perform(get(CATALOG_PATH + "/NOT_A_WORKLOAD")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldValidateSupportedResearchWorkloadCombination() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "workloadId": "NO_OP_TINY",
                          "protocol": "REST",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.reasonMessage").value(
                        "Workload can be used with the selected protocol combination."
                ));
    }

    @Test
    void shouldRejectUnsupportedProtocolCombinationForResearchWorkload() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "workloadId": "NO_OP_TINY",
                          "protocol": "SOAP",
                          "operation": "STREAM_GROUP_ACTIVITY",
                          "dataTransferMode": "STREAMED_EVENTS",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("PROTOCOL_COMBINATION_NOT_SUPPORTED"));
    }

    @Test
    void shouldAllowRestGroupCreationForWorkspaceWorkloadWhenProtocolSupportsWorkspaceArtifacts() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "workloadId": "AGENT_MERGE_OPTIMIZATION_4",
                          "protocol": "REST",
                          "operation": "CREATE_EXECUTION_GROUP",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()));
    }

    @Test
    void shouldRejectWorkspaceWorkloadWithUnsupportedProtocolCombinationFirst() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "workloadId": "FILE_IO_SMALL",
                          "protocol": "WEBSOCKET",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("PROTOCOL_COMBINATION_NOT_SUPPORTED"));
    }

    @Test
    void shouldRejectSingleExecutionOperationForGroupWorkload() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "workloadId": "SHARDED_OPTIMIZATION_4",
                          "protocol": "REST",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("WORKLOAD_REQUIRES_GROUP_OPERATION"));
    }

    @Test
    void shouldReturnUnknownWorkloadValidationResult() throws Exception {
        mockMvc.perform(validate("""
                        {
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
    void shouldEnforceAdminSecurityForResearchWorkloadCatalogEndpoints() throws Exception {
        WorkerCredentials credentials = createWorkerCredentials();

        mockMvc.perform(get(CATALOG_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(VALIDATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedNoOpValidationRequest()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(CATALOG_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(VALIDATE_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedNoOpValidationRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(CATALOG_PATH)
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(VALIDATE_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedNoOpValidationRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(validate(supportedNoOpValidationRequest()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldKeepResearchWorkloadCatalogResponsesSafe() throws Exception {
        String catalog = mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String validation = mockMvc.perform(validate(supportedNoOpValidationRequest()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeResearchWorkloadResponse(catalog);
        assertSafeResearchWorkloadResponse(validation);
    }

    @Test
    void shouldNotMutateRuntimeStateWhenReadingOrValidatingResearchWorkloadCatalog() throws Exception {
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

        mockMvc.perform(get(CATALOG_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(get(CATALOG_PATH + "/NO_OP_TINY")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(validate(supportedNoOpValidationRequest()))
                .andExpect(status().isOk());

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

    private MockHttpServletRequestBuilder validate(String body) {
        return post(VALIDATE_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String supportedNoOpValidationRequest() {
        return """
                {
                  "workloadId": "NO_OP_TINY",
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
                .hostname("m29-worker-" + UUID.randomUUID())
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

    private static void assertSafeResearchWorkloadResponse(String response) {
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
