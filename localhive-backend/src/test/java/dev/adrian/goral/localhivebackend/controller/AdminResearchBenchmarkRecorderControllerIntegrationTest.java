package dev.adrian.goral.localhivebackend.controller;

import com.jayway.jsonpath.JsonPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRun;
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

import java.time.LocalDateTime;
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
class AdminResearchBenchmarkRecorderControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m30-admin";
    private static final String BENCHMARK_PATH = "/api/admin/research/benchmark-runs";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void shouldCreateBenchmarkRunAndRejectBlankDisplayName() throws Exception {
        String response = mockMvc.perform(createRun(validRunRequest("Protocol comparison baseline")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.benchmarkRunId").exists())
                .andExpect(jsonPath("$.displayName").value("Protocol comparison baseline"))
                .andExpect(jsonPath("$.description").value("Manual run for REST/WebSocket/SOAP comparison."))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.notes").value("Initial recorder test."))
                .andExpect(jsonPath("$.scenarioCount").value(0))
                .andExpect(jsonPath("$.measurementCount").value(0))
                .andExpect(jsonPath("$.eventCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeBenchmarkResponse(response);

        mockMvc.perform(createRun("""
                        {
                          "displayName": "   "
                        }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldListAndReturnBenchmarkRunDetailWithCounts() throws Exception {
        BenchmarkRun olderRun = benchmarkRunRepository.save(BenchmarkRun.create(
                "Older run",
                null,
                "admin",
                null,
                null,
                LocalDateTime.now().minusDays(1)
        ));
        BenchmarkRun newerRun = benchmarkRunRepository.save(BenchmarkRun.create(
                "Newer run",
                null,
                "admin",
                null,
                null,
                LocalDateTime.now()
        ));

        String listResponse = mockMvc.perform(get(BENCHMARK_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].benchmarkRunId").value(newerRun.getId().toString()))
                .andExpect(jsonPath("$[1].benchmarkRunId").value(olderRun.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeBenchmarkResponse(listResponse);

        UUID benchmarkRunId = createRunId();
        UUID scenarioId = createValidScenario(benchmarkRunId);
        recordMeasurement(benchmarkRunId, scenarioId, "REQUEST_LATENCY_MS", "12.50", "ms");
        recordEvent(benchmarkRunId, scenarioId, "NOTE_RECORDED", "Manual note.", "{}");

        mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioCount").value(1))
                .andExpect(jsonPath("$.measurementCount").value(1))
                .andExpect(jsonPath("$.eventCount").value(4));
    }

    @Test
    void shouldApplyRunStatusTransitionsAndRejectTerminalTransitions() throws Exception {
        UUID benchmarkRunId = createRunId();

        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/start")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").exists());

        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/start")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/complete")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());

        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/fail")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"late failure\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldFailRunFromCreatedOrRunningAndSetCompletedAt() throws Exception {
        UUID createdRunId = createRunId();

        mockMvc.perform(post(BENCHMARK_PATH + "/" + createdRunId + "/fail")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"controlled failure\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.completedAt").exists());

        UUID runningRunId = createRunId();
        mockMvc.perform(post(BENCHMARK_PATH + "/" + runningRunId + "/start")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post(BENCHMARK_PATH + "/" + runningRunId + "/fail")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void shouldAddValidScenarioAndRejectInvalidWorkloadCombinationWithoutCreatingExecutions() throws Exception {
        UUID benchmarkRunId = createRunId();
        long executionCount = executionRepository.count();
        long groupCount = groupRepository.count();

        String response = mockMvc.perform(addScenario(benchmarkRunId, validScenarioRequest("REST sharded optimization")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.benchmarkScenarioId").exists())
                .andExpect(jsonPath("$.benchmarkRunId").value(benchmarkRunId.toString()))
                .andExpect(jsonPath("$.scenarioIndex").value(0))
                .andExpect(jsonPath("$.workloadId").value("SHARDED_OPTIMIZATION_4"))
                .andExpect(jsonPath("$.protocol").value("REST"))
                .andExpect(jsonPath("$.operation").value("CREATE_EXECUTION_GROUP"))
                .andExpect(jsonPath("$.dataTransferMode").value("INLINE_JSON"))
                .andExpect(jsonPath("$.payloadFormat").value("JSON"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.executionId").value(nullValue()))
                .andExpect(jsonPath("$.executionGroupId").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeBenchmarkResponse(response);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);

        mockMvc.perform(addScenario(benchmarkRunId, """
                        {
                          "displayName": "Invalid single/group mix",
                          "workloadId": "SHARDED_OPTIMIZATION_4",
                          "protocol": "REST",
                          "operation": "CREATE_SINGLE_EXECUTION",
                          "dataTransferMode": "INLINE_JSON",
                          "payloadFormat": "JSON"
                        }
                        """))
                .andExpect(status().isBadRequest());

        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);
    }

    @Test
    void shouldRejectAddingScenarioToTerminalRun() throws Exception {
        UUID benchmarkRunId = createRunId();
        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/complete")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(addScenario(benchmarkRunId, validScenarioRequest("Late scenario")))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldApplyScenarioTransitions() throws Exception {
        UUID benchmarkRunId = createRunId();
        UUID completedScenarioId = createValidScenario(benchmarkRunId);

        mockMvc.perform(post(scenarioPath(benchmarkRunId, completedScenarioId) + "/start")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").exists());

        mockMvc.perform(post(scenarioPath(benchmarkRunId, completedScenarioId) + "/complete")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.errorCode").value(nullValue()))
                .andExpect(jsonPath("$.errorMessage").value(nullValue()));

        mockMvc.perform(post(scenarioPath(benchmarkRunId, completedScenarioId) + "/fail")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorCode\":\"TOO_LATE\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());

        UUID failedScenarioId = createValidScenario(benchmarkRunId);
        mockMvc.perform(post(scenarioPath(benchmarkRunId, failedScenarioId) + "/fail")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "errorCode": "CONTROLLED_FAILURE",
                                  "errorMessage": "Authorization: Bearer abc"
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("CONTROLLED_FAILURE"))
                .andExpect(jsonPath("$.errorMessage").value("Authorization: Bearer <redacted>"));

        mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId + "/scenarios")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].scenarioIndex").value(0))
                .andExpect(jsonPath("$[1].scenarioIndex").value(1));
    }

    @Test
    void shouldRecordAndQueryMeasurementsIncludingTerminalRunPostProcessing() throws Exception {
        UUID benchmarkRunId = createRunId();
        UUID scenarioId = createValidScenario(benchmarkRunId);

        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/complete")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        String response = recordMeasurement(benchmarkRunId, scenarioId, "REQUEST_LATENCY_MS", "123.45", "ms");
        assertSafeBenchmarkResponse(response);

        mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/measurements")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "ERROR_COUNT",
                                  "valueNumeric": -1,
                                  "unit": "count"
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId + "/measurements")
                        .with(admin())
                        .queryParam("type", "REQUEST_LATENCY_MS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].benchmarkScenarioId").value(scenarioId.toString()))
                .andExpect(jsonPath("$[0].type").value("REQUEST_LATENCY_MS"))
                .andExpect(jsonPath("$[0].unit").value("ms"));

        UUID otherRunId = createRunId();
        mockMvc.perform(post(BENCHMARK_PATH + "/" + otherRunId + "/measurements")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scenarioId": "%s",
                                  "type": "ERROR_COUNT",
                                  "valueNumeric": 1,
                                  "unit": "count"
                                }
                                """.formatted(scenarioId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRecordSafeEventsAndRejectUnsafeMetadata() throws Exception {
        UUID benchmarkRunId = createRunId();
        UUID scenarioId = createValidScenario(benchmarkRunId);

        String response = recordEvent(
                benchmarkRunId,
                scenarioId,
                "NOTE_RECORDED",
                "Manual note.",
                "{\"latencyBucket\":\"p50\",\"count\":2}"
        );
        assertSafeBenchmarkResponse(response);

        mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId + "/events")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].type").value("NOTE_RECORDED"))
                .andExpect(jsonPath("$[2].metadataJson").value("{\"latencyBucket\":\"p50\",\"count\":2}"));

        List<String> unsafeMetadataSamples = List.of(
                "{\"token\":\"abc\"}",
                "{\"apiKey\":\"abc\"}",
                "{\"password\":\"abc\"}",
                "{\"leaseHash\":\"abc\"}",
                "{\"path\":\"C:\\\\Users\\\\adria\\\\secret.txt\"}",
                "{\"path\":\"/var/lib/localhive/secret.txt\"}"
        );

        for (String metadataJson : unsafeMetadataSamples) {
            mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/events")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eventRequest(scenarioId, "NOTE_RECORDED", "Unsafe note.", metadataJson))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void shouldEnforceAdminSecurityForBenchmarkRecorderEndpoints() throws Exception {
        WorkerCredentials credentials = createWorkerCredentials();
        String requestBody = validRunRequest("Security run");

        mockMvc.perform(get(BENCHMARK_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BENCHMARK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(BENCHMARK_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BENCHMARK_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(BENCHMARK_PATH)
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BENCHMARK_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(BENCHMARK_PATH)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(createRun(requestBody))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldKeepBenchmarkRecorderResponsesSafe() throws Exception {
        UUID benchmarkRunId = createRunId();
        UUID scenarioId = createValidScenario(benchmarkRunId);
        recordMeasurement(benchmarkRunId, scenarioId, "REQUEST_LATENCY_MS", "10", "ms");
        recordEvent(benchmarkRunId, scenarioId, "NOTE_RECORDED", "Safe note.", "{\"kind\":\"manual\"}");

        String run = mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String scenarios = mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId + "/scenarios")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String measurements = mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId + "/measurements")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String events = mockMvc.perform(get(BENCHMARK_PATH + "/" + benchmarkRunId + "/events")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeBenchmarkResponse(run);
        assertSafeBenchmarkResponse(scenarios);
        assertSafeBenchmarkResponse(measurements);
        assertSafeBenchmarkResponse(events);
    }

    @Test
    void shouldNotMutateRuntimeStateWhenRecordingBenchmarkData() throws Exception {
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

        UUID benchmarkRunId = createRunId();
        UUID scenarioId = createValidScenario(benchmarkRunId);
        recordMeasurement(benchmarkRunId, scenarioId, "REQUEST_LATENCY_MS", "10", "ms");
        recordEvent(benchmarkRunId, scenarioId, "NOTE_RECORDED", "Manual note.", "{}");

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

    private UUID createRunId() throws Exception {
        String response = mockMvc.perform(createRun(validRunRequest("Protocol comparison baseline " + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.benchmarkRunId"));
    }

    private UUID createValidScenario(UUID benchmarkRunId) throws Exception {
        String response = mockMvc.perform(addScenario(benchmarkRunId, validScenarioRequest("REST sharded optimization")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.benchmarkScenarioId"));
    }

    private String recordMeasurement(UUID benchmarkRunId,
                                     UUID scenarioId,
                                     String type,
                                     String valueNumeric,
                                     String unit) throws Exception {
        return mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/measurements")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scenarioId": "%s",
                                  "type": "%s",
                                  "valueNumeric": %s,
                                  "unit": "%s",
                                  "notes": "manual"
                                }
                                """.formatted(scenarioId, type, valueNumeric, unit))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String recordEvent(UUID benchmarkRunId,
                               UUID scenarioId,
                               String type,
                               String message,
                               String metadataJson) throws Exception {
        return mockMvc.perform(post(BENCHMARK_PATH + "/" + benchmarkRunId + "/events")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventRequest(scenarioId, type, message, metadataJson))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private MockHttpServletRequestBuilder createRun(String body) {
        return post(BENCHMARK_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder addScenario(UUID benchmarkRunId, String body) {
        return post(BENCHMARK_PATH + "/" + benchmarkRunId + "/scenarios")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String scenarioPath(UUID benchmarkRunId, UUID scenarioId) {
        return BENCHMARK_PATH + "/" + benchmarkRunId + "/scenarios/" + scenarioId;
    }

    private static String validRunRequest(String displayName) {
        return """
                {
                  "displayName": "%s",
                  "description": "Manual run for REST/WebSocket/SOAP comparison.",
                  "tags": ["baseline", "manual"],
                  "notes": "Initial recorder test."
                }
                """.formatted(displayName);
    }

    private static String validScenarioRequest(String displayName) {
        return """
                {
                  "displayName": "%s",
                  "workloadId": "SHARDED_OPTIMIZATION_4",
                  "protocol": "REST",
                  "operation": "CREATE_EXECUTION_GROUP",
                  "dataTransferMode": "INLINE_JSON",
                  "payloadFormat": "JSON",
                  "notes": "Manual scenario"
                }
                """.formatted(displayName);
    }

    private String eventRequest(UUID scenarioId, String type, String message, String metadataJson) throws Exception {
        return objectMapper.writeValueAsString(new EventRequest(
                scenarioId,
                type,
                message,
                metadataJson,
                UUID.randomUUID(),
                UUID.randomUUID()
        ));
    }

    private WorkerCredentials createWorkerCredentials() {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m30-worker-" + UUID.randomUUID())
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

    private static void assertSafeBenchmarkResponse(String response) {
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

    private record EventRequest(UUID scenarioId,
                                String type,
                                String message,
                                String metadataJson,
                                UUID relatedExecutionId,
                                UUID relatedExecutionGroupId) {
    }
}
