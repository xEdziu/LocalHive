package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEvent;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkEventType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurement;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkMeasurementType;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRun;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkScenario;
import dev.adrian.goral.localhivebackend.domain.research.ResearchDataTransferMode;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchPayloadFormat;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminResearchBenchmarkExportControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m32-admin";
    private static final String BENCHMARK_PATH = "/api/admin/research/benchmark-runs";
    private static final String SUMMARY_HEADER = "benchmarkRunId,displayName,status,protocol,workloadId,operation,scenarioCount,completedScenarioCount,failedScenarioCount,skippedScenarioCount,avgRequestLatencyMs,avgPayloadResponseBytes,totalErrorCount";
    private static final String SCENARIOS_HEADER = "benchmarkRunId,scenarioId,scenarioIndex,displayName,workloadId,protocol,operation,dataTransferMode,payloadFormat,status,createdAt,startedAt,completedAt,executionId,executionGroupId,errorCode,errorMessage,notes";
    private static final String MEASUREMENTS_HEADER = "benchmarkRunId,scenarioId,measurementId,type,valueNumeric,unit,recordedAt,notes";
    private static final String EVENTS_HEADER = "benchmarkRunId,scenarioId,eventId,type,occurredAt,message,metadataJson,relatedExecutionId,relatedExecutionGroupId";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 11, 15, 30);
    private static final MediaType TEXT_CSV = MediaType.valueOf("text/csv");

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
    void shouldReturnExportManifest() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();

        String response = mockMvc.perform(get(exportsPath(fixture.runId()))
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benchmarkRunId").value(fixture.runId().toString()))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.availableExports", hasSize(5)))
                .andExpect(jsonPath("$.availableExports[0].name").value("dataset-json"))
                .andExpect(jsonPath("$.availableExports[0].format").value("JSON"))
                .andExpect(jsonPath("$.availableExports[0].path")
                        .value(exportsPath(fixture.runId()) + "/dataset.json"))
                .andExpect(jsonPath("$.availableExports[1].name").value("summary-csv"))
                .andExpect(jsonPath("$.availableExports[2].name").value("scenarios-csv"))
                .andExpect(jsonPath("$.availableExports[3].name").value("measurements-csv"))
                .andExpect(jsonPath("$.availableExports[4].name").value("events-csv"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeExportResponse(response);
    }

    @Test
    void shouldExportCompleteJsonDatasetWithSummary() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();

        String response = mockMvc.perform(get(exportsPath(fixture.runId()) + "/dataset.json")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.benchmarkRun.benchmarkRunId").value(fixture.runId().toString()))
                .andExpect(jsonPath("$.benchmarkRun.displayName").value("=M32, \"Run\"\nDataset"))
                .andExpect(jsonPath("$.benchmarkRun.createdBy").doesNotExist())
                .andExpect(jsonPath("$.benchmarkRun.tags", hasSize(2)))
                .andExpect(jsonPath("$.scenarios", hasSize(3)))
                .andExpect(jsonPath("$.measurements", hasSize(6)))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.summary.scenarioCount").value(3))
                .andExpect(jsonPath("$.summary.measurementCount").value(6))
                .andExpect(jsonPath("$.summary.eventCount").value(2))
                .andExpect(jsonPath("$.summary.protocols", hasSize(2)))
                .andExpect(jsonPath("$.summary.protocols[0].protocol").value("REST"))
                .andExpect(jsonPath("$.summary.protocols[0].scenarioCount").value(2))
                .andExpect(jsonPath("$.summary.protocols[0].completedScenarioCount").value(1))
                .andExpect(jsonPath("$.summary.protocols[0].failedScenarioCount").value(1))
                .andExpect(jsonPath("$.summary.protocols[0].avgRequestLatencyMs").value(15))
                .andExpect(jsonPath("$.summary.protocols[0].avgPayloadResponseBytes").value(200))
                .andExpect(jsonPath("$.summary.protocols[0].errorCount").value(1))
                .andExpect(jsonPath("$.summary.protocols[1].protocol").value("SOAP"))
                .andExpect(jsonPath("$.summary.protocols[1].avgRequestLatencyMs").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeExportResponse(response);
    }

    @Test
    void shouldExportSummaryCsvWithAggregates() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();

        String csv = csv(exportsPath(fixture.runId()) + "/summary.csv");

        assertThat(firstLine(csv)).isEqualTo(SUMMARY_HEADER);
        assertThat(csv)
                .contains(",COMPLETED,REST,AGENT_MERGE_OPTIMIZATION_4,GET_GROUP_DETAIL,2,1,1,0,15,200,1")
                .contains(",COMPLETED,SOAP,AGENT_MERGE_OPTIMIZATION_4,GET_GROUP_ARTIFACTS,1,0,0,1,,,\n")
                .contains("\"'=M32, \"\"Run\"\"\nDataset\"");
        assertSafeExportResponse(csv);
    }

    @Test
    void shouldExportScenarioCsvWithDeterministicOrderingEscapingAndEmptyNulls() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();

        String csv = csv(exportsPath(fixture.runId()) + "/scenarios.csv");

        assertThat(firstLine(csv)).isEqualTo(SCENARIOS_HEADER);
        assertThat(csv.indexOf(fixture.firstScenarioId().toString()))
                .isLessThan(csv.indexOf(fixture.secondScenarioId().toString()));
        assertThat(csv.indexOf(fixture.secondScenarioId().toString()))
                .isLessThan(csv.indexOf(fixture.thirdScenarioId().toString()));
        assertThat(csv)
                .contains("\"Alpha, \"\"quoted\"\"\nline\"")
                .contains("'=Formula scenario")
                .contains("SKIPPED,2026-08-11T15:30:03,,2026-08-11T15:30:08,,,NOT_RUN,No shard matched,");
        assertSafeExportResponse(csv);
    }

    @Test
    void shouldExportMeasurementsCsvWithDeterministicOrderingAndFormulaMitigation() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();

        String csv = csv(exportsPath(fixture.runId()) + "/measurements.csv");

        assertThat(firstLine(csv)).isEqualTo(MEASUREMENTS_HEADER);
        assertThat(csv.indexOf(fixture.firstMeasurementId().toString()))
                .isLessThan(csv.indexOf(fixture.secondMeasurementId().toString()));
        assertThat(csv)
                .contains("REQUEST_LATENCY_MS,10,ms,2026-08-11T15:30:31,'@manual")
                .contains("PAYLOAD_RESPONSE_BYTES,100,bytes,2026-08-11T15:30:32,");
        assertSafeExportResponse(csv);
    }

    @Test
    void shouldExportEventsCsvWithDeterministicOrderingEscapingAndFormulaMitigation() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();

        String csv = csv(exportsPath(fixture.runId()) + "/events.csv");

        assertThat(firstLine(csv)).isEqualTo(EVENTS_HEADER);
        assertThat(csv.indexOf(fixture.firstEventId().toString()))
                .isLessThan(csv.indexOf(fixture.secondEventId().toString()));
        assertThat(csv)
                .contains("\"'+SUM(1,2)\"")
                .contains("\"{\"\"kind\"\":\"\"export\"\"}\"");
        assertSafeExportResponse(csv);
    }

    @Test
    void shouldReturnNotFoundForUnknownBenchmarkRun() throws Exception {
        UUID unknownRunId = UUID.randomUUID();

        mockMvc.perform(get(exportsPath(unknownRunId))
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(exportsPath(unknownRunId) + "/dataset.json")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(exportsPath(unknownRunId) + "/summary.csv")
                        .with(admin())
                        .accept(MediaType.ALL))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldEnforceAdminSecurityForExportEndpoints() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();
        WorkerCredentials credentials = createWorkerCredentials();

        mockMvc.perform(get(exportsPath(fixture.runId())).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(exportsPath(fixture.runId()))
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(exportsPath(fixture.runId()))
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(exportsPath(fixture.runId()))
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void shouldKeepExportsSafeAndReadOnly() throws Exception {
        BenchmarkFixture fixture = createBenchmarkFixture();
        long runCount = benchmarkRunRepository.count();
        long scenarioCount = benchmarkScenarioRepository.count();
        long measurementCount = benchmarkMeasurementRepository.count();
        long eventCount = benchmarkEventRepository.count();
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

        String manifest = json(exportsPath(fixture.runId()));
        String dataset = json(exportsPath(fixture.runId()) + "/dataset.json");
        String summary = csv(exportsPath(fixture.runId()) + "/summary.csv");
        String scenarios = csv(exportsPath(fixture.runId()) + "/scenarios.csv");
        String measurements = csv(exportsPath(fixture.runId()) + "/measurements.csv");
        String events = csv(exportsPath(fixture.runId()) + "/events.csv");

        assertSafeExportResponse(manifest);
        assertSafeExportResponse(dataset);
        assertSafeExportResponse(summary);
        assertSafeExportResponse(scenarios);
        assertSafeExportResponse(measurements);
        assertSafeExportResponse(events);

        assertThat(benchmarkRunRepository.count()).isEqualTo(runCount);
        assertThat(benchmarkScenarioRepository.count()).isEqualTo(scenarioCount);
        assertThat(benchmarkMeasurementRepository.count()).isEqualTo(measurementCount);
        assertThat(benchmarkEventRepository.count()).isEqualTo(eventCount);
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

    private String json(String path) throws Exception {
        return mockMvc.perform(get(path)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String csv(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .with(admin())
                        .accept(TEXT_CSV))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_CSV))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private BenchmarkFixture createBenchmarkFixture() {
        ArrayNode tags = objectMapper.createArrayNode()
                .add("m32")
                .add("thesis");
        BenchmarkRun run = BenchmarkRun.create(
                "=M32, \"Run\"\nDataset",
                "Export dataset for thesis.",
                "admin",
                tags,
                "Safe notes.",
                BASE_TIME
        );
        run.start(BASE_TIME.plusSeconds(10));
        run.complete(BASE_TIME.plusSeconds(90));
        run = benchmarkRunRepository.saveAndFlush(run);

        BenchmarkScenario firstScenario = BenchmarkScenario.create(
                run,
                0,
                "Alpha, \"quoted\"\nline",
                "AGENT_MERGE_OPTIMIZATION_4",
                ResearchProtocol.REST,
                ResearchOperation.GET_GROUP_DETAIL,
                ResearchDataTransferMode.INLINE_JSON,
                ResearchPayloadFormat.JSON,
                null,
                BASE_TIME.plusSeconds(1)
        );
        firstScenario.start(BASE_TIME.plusSeconds(11));
        firstScenario.complete(BASE_TIME.plusSeconds(21));
        firstScenario = benchmarkScenarioRepository.saveAndFlush(firstScenario);

        BenchmarkScenario secondScenario = BenchmarkScenario.create(
                run,
                1,
                "=Formula scenario",
                "AGENT_MERGE_OPTIMIZATION_4",
                ResearchProtocol.REST,
                ResearchOperation.GET_GROUP_DETAIL,
                ResearchDataTransferMode.INLINE_JSON,
                ResearchPayloadFormat.JSON,
                "Failure path note.",
                BASE_TIME.plusSeconds(2)
        );
        secondScenario.start(BASE_TIME.plusSeconds(12));
        secondScenario.fail("CONTROLLED_FAILURE", "Safe failure.", BASE_TIME.plusSeconds(22));
        secondScenario = benchmarkScenarioRepository.saveAndFlush(secondScenario);

        BenchmarkScenario thirdScenario = BenchmarkScenario.create(
                run,
                2,
                "SOAP artifacts",
                "AGENT_MERGE_OPTIMIZATION_4",
                ResearchProtocol.SOAP,
                ResearchOperation.GET_GROUP_ARTIFACTS,
                ResearchDataTransferMode.INLINE_XML,
                ResearchPayloadFormat.XML,
                null,
                BASE_TIME.plusSeconds(3)
        );
        thirdScenario.skip("NOT_RUN", "No shard matched", BASE_TIME.plusSeconds(8));
        thirdScenario = benchmarkScenarioRepository.saveAndFlush(thirdScenario);

        BenchmarkMeasurement secondMeasurement = benchmarkMeasurementRepository.saveAndFlush(BenchmarkMeasurement.create(
                run,
                firstScenario,
                BenchmarkMeasurementType.PAYLOAD_RESPONSE_BYTES,
                new BigDecimal("100.0000"),
                "bytes",
                null,
                BASE_TIME.plusSeconds(32)
        ));
        BenchmarkMeasurement firstMeasurement = benchmarkMeasurementRepository.saveAndFlush(BenchmarkMeasurement.create(
                run,
                firstScenario,
                BenchmarkMeasurementType.REQUEST_LATENCY_MS,
                new BigDecimal("10.0000"),
                "ms",
                "@manual",
                BASE_TIME.plusSeconds(31)
        ));
        benchmarkMeasurementRepository.saveAndFlush(BenchmarkMeasurement.create(
                run,
                firstScenario,
                BenchmarkMeasurementType.ERROR_COUNT,
                BigDecimal.ZERO,
                "count",
                null,
                BASE_TIME.plusSeconds(33)
        ));
        benchmarkMeasurementRepository.saveAndFlush(BenchmarkMeasurement.create(
                run,
                secondScenario,
                BenchmarkMeasurementType.REQUEST_LATENCY_MS,
                new BigDecimal("20.0000"),
                "ms",
                null,
                BASE_TIME.plusSeconds(34)
        ));
        benchmarkMeasurementRepository.saveAndFlush(BenchmarkMeasurement.create(
                run,
                secondScenario,
                BenchmarkMeasurementType.PAYLOAD_RESPONSE_BYTES,
                new BigDecimal("300.0000"),
                "bytes",
                null,
                BASE_TIME.plusSeconds(35)
        ));
        benchmarkMeasurementRepository.saveAndFlush(BenchmarkMeasurement.create(
                run,
                secondScenario,
                BenchmarkMeasurementType.ERROR_COUNT,
                BigDecimal.ONE,
                "count",
                null,
                BASE_TIME.plusSeconds(36)
        ));

        BenchmarkEvent secondEvent = benchmarkEventRepository.saveAndFlush(BenchmarkEvent.create(
                run,
                secondScenario,
                BenchmarkEventType.NOTE_RECORDED,
                "Second event.",
                "{\"kind\":\"later\"}",
                null,
                null,
                BASE_TIME.plusSeconds(40)
        ));
        BenchmarkEvent firstEvent = benchmarkEventRepository.saveAndFlush(BenchmarkEvent.create(
                run,
                firstScenario,
                BenchmarkEventType.NOTE_RECORDED,
                "+SUM(1,2)",
                "{\"kind\":\"export\"}",
                null,
                null,
                BASE_TIME.plusSeconds(30)
        ));

        return new BenchmarkFixture(
                run.getId(),
                firstScenario.getId(),
                secondScenario.getId(),
                thirdScenario.getId(),
                firstMeasurement.getId(),
                secondMeasurement.getId(),
                firstEvent.getId(),
                secondEvent.getId()
        );
    }

    private WorkerCredentials createWorkerCredentials() {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m32-worker-" + UUID.randomUUID())
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

    private static String exportsPath(UUID benchmarkRunId) {
        return BENCHMARK_PATH + "/" + benchmarkRunId + "/exports";
    }

    private static String firstLine(String csv) {
        return csv.substring(0, csv.indexOf('\n'));
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private static void assertSafeExportResponse(String response) {
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

    private record BenchmarkFixture(
            UUID runId,
            UUID firstScenarioId,
            UUID secondScenarioId,
            UUID thirdScenarioId,
            UUID firstMeasurementId,
            UUID secondMeasurementId,
            UUID firstEventId,
            UUID secondEventId
    ) {
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
