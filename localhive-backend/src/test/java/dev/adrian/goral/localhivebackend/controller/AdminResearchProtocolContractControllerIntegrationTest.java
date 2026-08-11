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
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminResearchProtocolContractControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m26-admin";
    private static final String CONTRACT_PATH = "/api/admin/research/protocol-contract";
    private static final String VALIDATE_PATH = "/api/admin/research/protocol-contract/validate";

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
    private ExecutionGroupRepository groupRepository;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void resetDatabase() {
        executionArtifactRepository.deleteAll();
        artifactRepository.deleteAll();
        assignmentRepository.deleteAll();
        executionRepository.deleteAll();
        groupRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(User.builder()
                .username(ADMIN_USERNAME + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    @Test
    void shouldReturnResearchProtocolContractForAdmin() throws Exception {
        String response = mockMvc.perform(get(CONTRACT_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.protocols").isArray())
                .andExpect(jsonPath("$.operations").isArray())
                .andExpect(jsonPath("$.dataTransferModes").isArray())
                .andExpect(jsonPath("$.payloadFormats").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<List<String>>read(response, "$.protocols[*].protocol"))
                .containsExactly("REST", "WEBSOCKET", "SOAP");
        assertThat(JsonPath.<List<String>>read(response, "$.protocols[*].status"))
                .containsExactly("AVAILABLE", "AVAILABLE", "AVAILABLE");
        assertThat(JsonPath.<List<String>>read(response, "$.operations[*].operation"))
                .contains(
                        "CREATE_SINGLE_EXECUTION",
                        "CREATE_EXECUTION_GROUP",
                        "GET_EXECUTION_STATUS",
                        "GET_GROUP_DETAIL",
                        "GET_GROUP_ACTIVITY",
                        "GET_GROUP_ARTIFACTS",
                        "STREAM_GROUP_ACTIVITY",
                        "STOP_STREAM_GROUP_ACTIVITY",
                        "DOWNLOAD_ARTIFACT",
                        "CANCEL_GROUP",
                        "RECONCILE_GROUP"
                );
        assertThat(JsonPath.<List<String>>read(response, "$.dataTransferModes[*].mode"))
                .contains(
                        "INLINE_JSON",
                        "INLINE_XML",
                        "WORKSPACE_ARTIFACT",
                        "OUTPUT_ARTIFACT",
                        "STREAMED_EVENTS"
                );
        assertThat(JsonPath.<List<String>>read(response, "$.payloadFormats[*].format"))
                .contains("JSON", "XML", "BINARY", "MULTIPART");
        assertThat(JsonPath.<List<String>>read(response, "$.protocols[?(@.protocol == 'REST')].supportedOperations[*]"))
                .contains("CREATE_EXECUTION_GROUP", "STREAM_GROUP_ACTIVITY", "DOWNLOAD_ARTIFACT");
        assertThat(JsonPath.<List<String>>read(response, "$.protocols[?(@.protocol == 'WEBSOCKET')].supportedOperations[*]"))
                .contains("GET_GROUP_DETAIL", "STREAM_GROUP_ACTIVITY", "STOP_STREAM_GROUP_ACTIVITY")
                .doesNotContain("DOWNLOAD_ARTIFACT");
        assertThat(JsonPath.<List<String>>read(response, "$.protocols[?(@.protocol == 'SOAP')].supportedOperations[*]"))
                .contains("GET_GROUP_DETAIL", "GET_GROUP_ACTIVITY", "GET_GROUP_ARTIFACTS", "CANCEL_GROUP", "RECONCILE_GROUP")
                .doesNotContain("CREATE_EXECUTION_GROUP", "STREAM_GROUP_ACTIVITY", "DOWNLOAD_ARTIFACT");
        assertSafeResearchResponse(response);
    }

    @Test
    void shouldValidateSupportedRestCombination() throws Exception {
        mockMvc.perform(validate(supportedRestGroupCreateRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.reasonMessage").value("Combination is supported."));
    }

    @Test
    void shouldValidateSupportedWebSocketCombinations() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "protocol": "WEBSOCKET",
                          "operation": "GET_GROUP_DETAIL",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.reasonMessage").value("Combination is supported."));

        mockMvc.perform(validate("""
                        {
                          "protocol": "WEBSOCKET",
                          "operation": "STREAM_GROUP_ACTIVITY",
                          "dataTransferMode": "STREAMED_EVENTS",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.reasonMessage").value("Combination is supported."));
    }

    @Test
    void shouldRejectUnsupportedWebSocketCombinationAsUnsupportedModelResult() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "protocol": "WEBSOCKET",
                          "operation": "CREATE_EXECUTION_GROUP",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("OPERATION_NOT_SUPPORTED"));
    }

    @Test
    void shouldValidateSupportedSoapCombinations() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "GET_GROUP_DETAIL",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.reasonMessage").value("Combination is supported."));

        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "GET_GROUP_ACTIVITY",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "GET_GROUP_ARTIFACTS",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "CANCEL_GROUP",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "RECONCILE_GROUP",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void shouldRejectUnsupportedSoapCombinationsAsUnsupportedModelResult() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "STREAM_GROUP_ACTIVITY",
                          "dataTransferMode": "STREAMED_EVENTS",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("OPERATION_NOT_SUPPORTED"));

        mockMvc.perform(validate("""
                        {
                          "protocol": "SOAP",
                          "operation": "DOWNLOAD_ARTIFACT",
                          "dataTransferMode": "OUTPUT_ARTIFACT",
                          "payloadFormat": "BINARY"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("OPERATION_NOT_SUPPORTED"));
    }

    @Test
    void shouldRejectUnsupportedRestCombinationAsUnsupportedModelResult() throws Exception {
        mockMvc.perform(validate("""
                        {
                          "protocol": "REST",
                          "operation": "STREAM_GROUP_ACTIVITY",
                          "dataTransferMode": "INLINE_XML",
                          "payloadFormat": "XML"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reasonCode").value("DATA_TRANSFER_MODE_NOT_SUPPORTED"));
    }

    @Test
    void shouldEnforceAdminSecurityForResearchProtocolContractEndpoints() throws Exception {
        WorkerCredentials credentials = createWorkerCredentials();

        mockMvc.perform(get(CONTRACT_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(VALIDATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedRestGroupCreateRequest()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(CONTRACT_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(VALIDATE_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedRestGroupCreateRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(CONTRACT_PATH)
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(VALIDATE_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(supportedRestGroupCreateRequest())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(CONTRACT_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(validate(supportedRestGroupCreateRequest()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldKeepResearchProtocolContractResponsesSafe() throws Exception {
        String contract = mockMvc.perform(get(CONTRACT_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String validation = mockMvc.perform(validate(supportedRestGroupCreateRequest()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeResearchResponse(contract);
        assertSafeResearchResponse(validation);
    }

    @Test
    void shouldNotMutateRuntimeStateWhenReadingOrValidatingResearchProtocolContract() throws Exception {
        long artifactCount = artifactRepository.count();
        long executionArtifactCount = executionArtifactRepository.count();
        long assignmentCount = assignmentRepository.count();
        long executionCount = executionRepository.count();
        long groupCount = groupRepository.count();
        long workerCount = workerRepository.count();

        mockMvc.perform(get(CONTRACT_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(validate(supportedRestGroupCreateRequest()))
                .andExpect(status().isOk());

        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(executionArtifactRepository.count()).isEqualTo(executionArtifactCount);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);
        assertThat(workerRepository.count()).isEqualTo(workerCount);
    }

    private MockHttpServletRequestBuilder validate(String body) {
        return post(VALIDATE_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String supportedRestGroupCreateRequest() {
        return """
                {
                  "protocol": "REST",
                  "operation": "CREATE_EXECUTION_GROUP",
                  "dataTransferMode": "INLINE_JSON",
                  "payloadFormat": "JSON"
                }
                """;
    }

    private WorkerCredentials createWorkerCredentials() {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m26-worker-" + UUID.randomUUID())
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

    private static void assertSafeResearchResponse(String response) {
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
