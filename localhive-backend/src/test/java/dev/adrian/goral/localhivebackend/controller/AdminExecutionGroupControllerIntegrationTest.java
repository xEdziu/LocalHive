package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
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
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactManagementService;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactStorageService;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.ExecutionGroupCancellationService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionLifecycleService;
import dev.adrian.goral.localhivebackend.service.work.WorkerExecutionReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "localhive.artifacts.storage-root=target/test-artifacts/execution-groups")
@AutoConfigureMockMvc
class AdminExecutionGroupControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m17-admin";
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-21T10:00:00");

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
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

    @Autowired
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkExecutionLifecycleService lifecycleService;

    @Autowired
    private WorkerExecutionReportService reportService;

    @Autowired
    private ArtifactManagementService artifactManagementService;

    @Autowired
    private ArtifactStorageService artifactStorageService;

    @Autowired
    private ExecutionGroupRepository groupRepository;

    @Autowired
    private ExecutionGroupMergePlanRepository mergePlanRepository;

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
    private WorkerCapabilitiesRepository workerCapabilitiesRepository;

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
        mergePlanRepository.deleteAll();
        groupRepository.deleteAll();
        instanceRepository.deleteAll();
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        workerCapabilitiesRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser(ADMIN_USERNAME);
    }

    @Test
    void shouldEnforceAdminSecurityForExecutionGroupEndpoints() throws Exception {
        WorkerCredentials credentials = createApprovedWorkerCredentials("security");
        ExecutionGroup group = createGroup("Security group", 1);
        WorkDefinitionVersion version = dockerVersion();
        String createRequest = shardedCreateRequest(
                version.getId(),
                "Security create group",
                1,
                "AUTO",
                null,
                "FAIL_FAST"
        );

        mockMvc.perform(get("/api/admin/execution-groups")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/executions", group.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity/stream", group.getId())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/execution-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups")
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/executions", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity/stream", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/execution-groups")
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/execution-groups")
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/executions", group.getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity/stream", group.getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(post("/api/admin/execution-groups")
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/execution-groups")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/executions", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        activityStreamResponse(group.getId(), "maxEvents", "2");

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionGroupId").exists());

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void shouldListExecutionGroupsWithPaginationAndStatusFilter() throws Exception {
        ExecutionGroup olderGroup = createGroup("Older group", 2);
        Thread.sleep(5);
        ExecutionGroup newerGroup = createGroup("Newer group", 3);
        createShardExecution(newerGroup, noOpVersion(), createApprovedWorker("list-worker"), 0, 3, "List shard");

        String response = mockMvc.perform(get("/api/admin/execution-groups")
                        .param("status", "CREATED")
                        .param("limit", "1")
                        .param("offset", "0")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].executionGroupId").value(newerGroup.getId().toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Newer group"))
                .andExpect(jsonPath("$.items[0].status").value("CREATED"))
                .andExpect(jsonPath("$.items[0].mergeMode").value("NONE"))
                .andExpect(jsonPath("$.items[0].failurePolicy").value("FAIL_FAST"))
                .andExpect(jsonPath("$.items[0].shardCount").value(3))
                .andExpect(jsonPath("$.items[0].totalExecutions").value(1))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].updatedAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);

        mockMvc.perform(get("/api/admin/execution-groups")
                        .param("limit", "1")
                        .param("offset", "1")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].executionGroupId").value(olderGroup.getId().toString()))
                .andExpect(jsonPath("$.totalCount").value(2));

        mockMvc.perform(get("/api/admin/execution-groups")
                        .param("status", "NOT_A_STATUS")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown execution group status: NOT_A_STATUS"));

        mockMvc.perform(get("/api/admin/execution-groups")
                        .param("offset", "-1")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("offset must be greater than or equal to 0."));

        mockMvc.perform(get("/api/admin/execution-groups")
                        .param("limit", "201")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200."));
    }

    @Test
    void shouldReturnExecutionGroupDetailWithChildStatusCounts() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Detail group", 2);
        Worker assignedWorker = createApprovedWorker("detail-assigned");
        Worker succeededWorker = createApprovedWorker("detail-succeeded");
        createShardExecution(group, version, assignedWorker, 0, 2, "Assigned shard");
        WorkExecution succeeded = createShardExecution(group, version, succeededWorker, 1, 2, "Succeeded shard");
        lifecycleService.markClaimed(succeeded.getId(), BASE_TIME.plusSeconds(1));
        lifecycleService.markRunning(succeeded.getId(), BASE_TIME.plusSeconds(2));
        lifecycleService.markSucceeded(succeeded.getId(), BASE_TIME.plusSeconds(5));

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionGroupId").value(group.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Detail group"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.mergeMode").value("NONE"))
                .andExpect(jsonPath("$.failurePolicy").value("FAIL_FAST"))
                .andExpect(jsonPath("$.shardCount").value(2))
                .andExpect(jsonPath("$.totalExecutions").value(2))
                .andExpect(jsonPath("$.activeExecutions").value(1))
                .andExpect(jsonPath("$.terminalExecutions").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.SUCCEEDED").value(1))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.failureMessage").value(nullValue()))
                .andExpect(jsonPath("$.observability.terminal").value(false))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(false))
                .andExpect(jsonPath("$.observability.hasActiveChildren").value(false))
                .andExpect(jsonPath("$.observability.hasQueuedChildren").value(false))
                .andExpect(jsonPath("$.observability.canCancel").value(true))
                .andExpect(jsonPath("$.observability.canReconcile").value(true))
                .andExpect(jsonPath("$.lifecycleActions.cancel.available").value(true))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonMessage").value("Group can be cancelled."))
                .andExpect(jsonPath("$.lifecycleActions.cancel.method").value("POST"))
                .andExpect(jsonPath("$.lifecycleActions.cancel.path")
                        .value("/api/admin/execution-groups/{executionGroupId}/cancel"))
                .andExpect(jsonPath("$.lifecycleActions.cancel.requiresBody").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonSupported").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.available").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonMessage").value("Group can be reconciled."))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.method").value("POST"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.path")
                        .value("/api/admin/execution-groups/{executionGroupId}/reconcile"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.requiresBody").value(false))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonSupported").value(false))
                .andExpect(jsonPath("$.observability.shards.total").value(2))
                .andExpect(jsonPath("$.observability.shards.assigned").value(1))
                .andExpect(jsonPath("$.observability.shards.succeeded").value(1))
                .andExpect(jsonPath("$.observability.shards.terminal").value(1))
                .andExpect(jsonPath("$.observability.shards.nonTerminal").value(1))
                .andExpect(jsonPath("$.observability.merge.exists").value(false))
                .andExpect(jsonPath("$.observability.merge.executionId").value(nullValue()))
                .andExpect(jsonPath("$.observability.merge.status").value(nullValue()))
                .andExpect(jsonPath("$.observability.merge.workerId").value(nullValue()))
                .andExpect(jsonPath("$.observability.merge.workerHostname").value(nullValue()))
                .andExpect(jsonPath("$.observability.merge.total").value(0))
                .andExpect(jsonPath("$.artifactSummary.totalArtifacts").value(0))
                .andExpect(jsonPath("$.artifactSummary.shardArtifacts").value(0))
                .andExpect(jsonPath("$.artifactSummary.mergeArtifacts").value(0))
                .andExpect(jsonPath("$.artifactSummary.shardsWithArtifacts").value(0))
                .andExpect(jsonPath("$.artifactSummary.mergeHasArtifacts").value(false))
                .andExpect(jsonPath("$.artifactSummary.preferredOutputSource").value("NONE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);

        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution group not found."));
    }

    @Test
    void shouldReturnOnlyChildExecutionsForExecutionGroup() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Children group", 3);
        WorkExecution thirdChild = createQueuedShardExecution(group, version, 2, 3, "Third child");
        Thread.sleep(5);
        WorkExecution firstChild = createQueuedShardExecution(group, version, 0, 3, "First child");
        Thread.sleep(5);
        WorkExecution mergeChild = createQueuedMergeExecution(group, version, 3, "Merge child");
        Thread.sleep(5);
        WorkExecution secondChild = createQueuedShardExecution(group, version, 1, 3, "Second child");
        WorkExecution standalone = createOneOff(version, "Standalone execution");

        String response = mockMvc.perform(get(
                        "/api/admin/execution-groups/{executionGroupId}/executions",
                        group.getId()
                )
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].executionId").value(firstChild.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("QUEUED"))
                .andExpect(jsonPath("$[0].assignmentMode").value(nullValue()))
                .andExpect(jsonPath("$[0].workerId").value(nullValue()))
                .andExpect(jsonPath("$[0].workerHostname").value(nullValue()))
                .andExpect(jsonPath("$[0].groupRole").value("SHARD"))
                .andExpect(jsonPath("$[0].shardIndex").value(0))
                .andExpect(jsonPath("$[0].shardCount").value(3))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].executionId").value(secondChild.getId().toString()))
                .andExpect(jsonPath("$[1].groupRole").value("SHARD"))
                .andExpect(jsonPath("$[1].shardIndex").value(1))
                .andExpect(jsonPath("$[2].executionId").value(thirdChild.getId().toString()))
                .andExpect(jsonPath("$[2].groupRole").value("SHARD"))
                .andExpect(jsonPath("$[2].shardIndex").value(2))
                .andExpect(jsonPath("$[3].executionId").value(mergeChild.getId().toString()))
                .andExpect(jsonPath("$[3].groupRole").value("MERGE"))
                .andExpect(jsonPath("$[3].shardIndex").value(nullValue()))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(standalone.getId().toString()))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);

        String adminExecutionDetailResponse = mockMvc.perform(get(
                        "/api/admin/executions/{executionId}",
                        secondChild.getId()
                )
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupMetadata.executionGroupId").value(group.getId().toString()))
                .andExpect(jsonPath("$.groupMetadata.groupRole").value("SHARD"))
                .andExpect(jsonPath("$.groupMetadata.shardIndex").value(1))
                .andExpect(jsonPath("$.groupMetadata.shardCount").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(adminExecutionDetailResponse);
    }

    @Test
    void shouldListShardOnlyGroupArtifactsWithPreferredShardOutputsAndDeterministicSorting() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Shard artifact group", 2);
        Worker shardOneWorker = createApprovedWorker("artifact-shard-one");
        Worker shardZeroWorker = createApprovedWorker("artifact-shard-zero");
        WorkExecution shardOne = createShardExecution(group, version, shardOneWorker, 1, 2, "Shard one");
        WorkExecution shardZero = createShardExecution(group, version, shardZeroWorker, 0, 2, "Shard zero");
        ExecutionArtifact zeta = createOutputArtifact(
                shardOne,
                shardOneWorker,
                "zeta.json",
                "zeta.json",
                BASE_TIME.plusSeconds(4)
        );
        ExecutionArtifact beta = createOutputArtifact(
                shardZero,
                shardZeroWorker,
                "beta.json",
                "beta.json",
                BASE_TIME.plusSeconds(3)
        );
        ExecutionArtifact alpha = createOutputArtifact(
                shardZero,
                shardZeroWorker,
                "alpha.json",
                "alpha.json",
                BASE_TIME.plusSeconds(5)
        );

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionGroupId").value(group.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Shard artifact group"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.mergeMode").value("NONE"))
                .andExpect(jsonPath("$.artifactSummary.totalArtifacts").value(3))
                .andExpect(jsonPath("$.artifactSummary.shardArtifacts").value(3))
                .andExpect(jsonPath("$.artifactSummary.mergeArtifacts").value(0))
                .andExpect(jsonPath("$.artifactSummary.shardsWithArtifacts").value(2))
                .andExpect(jsonPath("$.artifactSummary.mergeHasArtifacts").value(false))
                .andExpect(jsonPath("$.artifactSummary.preferredOutputSource").value("SHARDS"))
                .andExpect(jsonPath("$.shards", hasSize(2)))
                .andExpect(jsonPath("$.shards[0].shardIndex").value(0))
                .andExpect(jsonPath("$.shards[0].executionId").value(shardZero.getId().toString()))
                .andExpect(jsonPath("$.shards[0].workerId").value(shardZeroWorker.getId().toString()))
                .andExpect(jsonPath("$.shards[0].artifactCount").value(2))
                .andExpect(jsonPath("$.shards[0].artifacts[0].artifactId").value(alpha.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.shards[0].artifacts[0].groupRole").value("SHARD"))
                .andExpect(jsonPath("$.shards[0].artifacts[0].relativePath").value("alpha.json"))
                .andExpect(jsonPath("$.shards[0].artifacts[1].artifactId").value(beta.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.shards[0].artifacts[1].relativePath").value("beta.json"))
                .andExpect(jsonPath("$.shards[1].shardIndex").value(1))
                .andExpect(jsonPath("$.shards[1].executionId").value(shardOne.getId().toString()))
                .andExpect(jsonPath("$.shards[1].artifactCount").value(1))
                .andExpect(jsonPath("$.merge.exists").value(false))
                .andExpect(jsonPath("$.merge.mergeExecutionCount").value(0))
                .andExpect(jsonPath("$.merge.artifactCount").value(0))
                .andExpect(jsonPath("$.preferredOutputs", hasSize(3)))
                .andExpect(jsonPath("$.preferredOutputs[0].artifactId").value(alpha.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.preferredOutputs[0].relativePath").value("alpha.json"))
                .andExpect(jsonPath("$.preferredOutputs[1].artifactId").value(beta.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.preferredOutputs[1].relativePath").value("beta.json"))
                .andExpect(jsonPath("$.preferredOutputs[2].artifactId").value(zeta.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.preferredOutputs[2].relativePath").value("zeta.json"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeGroupArtifactResponse(response);
    }

    @Test
    void shouldListAgentMergeGroupArtifactsWithPreferredMergeOutputs() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup(
                "Agent merge artifact group",
                2,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.FAIL_FAST
        );
        Worker shardZeroWorker = createApprovedWorker("artifact-agent-shard-zero");
        Worker shardOneWorker = createApprovedWorker("artifact-agent-shard-one");
        Worker mergeWorker = createApprovedWorker("artifact-agent-merge");
        WorkExecution shardZero = createShardExecution(group, version, shardZeroWorker, 0, 2, "Shard zero");
        WorkExecution shardOne = createShardExecution(group, version, shardOneWorker, 1, 2, "Shard one");
        WorkExecution merge = createQueuedMergeExecution(group, version, 2, "Merge child");
        assignmentService.assignExecution(
                merge.getId(),
                mergeWorker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusSeconds(10)
        );
        createOutputArtifact(shardZero, shardZeroWorker, "shard-zero.json", "shard-zero.json", BASE_TIME.plusSeconds(2));
        createOutputArtifact(shardOne, shardOneWorker, "shard-one.json", "shard-one.json", BASE_TIME.plusSeconds(3));
        ExecutionArtifact finalResult = createOutputArtifact(
                merge,
                mergeWorker,
                "final-result.json",
                "final-result.json",
                BASE_TIME.plusSeconds(11)
        );
        ExecutionArtifact finalSummary = createOutputArtifact(
                merge,
                mergeWorker,
                "summary/final.txt",
                "final.txt",
                BASE_TIME.plusSeconds(12)
        );

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeMode").value("AGENT"))
                .andExpect(jsonPath("$.artifactSummary.totalArtifacts").value(4))
                .andExpect(jsonPath("$.artifactSummary.shardArtifacts").value(2))
                .andExpect(jsonPath("$.artifactSummary.mergeArtifacts").value(2))
                .andExpect(jsonPath("$.artifactSummary.shardsWithArtifacts").value(2))
                .andExpect(jsonPath("$.artifactSummary.mergeHasArtifacts").value(true))
                .andExpect(jsonPath("$.artifactSummary.preferredOutputSource").value("MERGE"))
                .andExpect(jsonPath("$.shards", hasSize(2)))
                .andExpect(jsonPath("$.merge.exists").value(true))
                .andExpect(jsonPath("$.merge.mergeExecutionCount").value(1))
                .andExpect(jsonPath("$.merge.executionId").value(merge.getId().toString()))
                .andExpect(jsonPath("$.merge.executionStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.merge.workerId").value(mergeWorker.getId().toString()))
                .andExpect(jsonPath("$.merge.artifactCount").value(2))
                .andExpect(jsonPath("$.merge.artifacts[0].artifactId").value(finalResult.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.merge.artifacts[0].groupRole").value("MERGE"))
                .andExpect(jsonPath("$.merge.artifacts[0].shardIndex").value(nullValue()))
                .andExpect(jsonPath("$.merge.artifacts[0].relativePath").value("final-result.json"))
                .andExpect(jsonPath("$.merge.artifacts[1].artifactId").value(finalSummary.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.merge.artifacts[1].relativePath").value("summary/final.txt"))
                .andExpect(jsonPath("$.preferredOutputs", hasSize(2)))
                .andExpect(jsonPath("$.preferredOutputs[0].artifactId").value(finalResult.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.preferredOutputs[0].groupRole").value("MERGE"))
                .andExpect(jsonPath("$.preferredOutputs[1].artifactId").value(finalSummary.getArtifact().getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeGroupArtifactResponse(response);
    }

    @Test
    void shouldExposePartialArtifactsForCancelledGroupWithoutCreatingMergeExpectation() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Cancelled partial artifact group", 2);
        Worker worker = createApprovedWorker("artifact-cancelled");
        WorkExecution shardWithOutput = createShardExecution(group, version, worker, 0, 2, "Shard with output");
        createQueuedShardExecution(group, version, 1, 2, "Cancelled shard without output");
        ExecutionArtifact artifact = createOutputArtifact(
                shardWithOutput,
                worker,
                "partial/result.json",
                "result.json",
                BASE_TIME.plusSeconds(7)
        );
        group.markCancelled(
                ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME.plusSeconds(8)
        );
        groupRepository.saveAndFlush(group);

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.artifactSummary.totalArtifacts").value(1))
                .andExpect(jsonPath("$.artifactSummary.shardArtifacts").value(1))
                .andExpect(jsonPath("$.artifactSummary.mergeArtifacts").value(0))
                .andExpect(jsonPath("$.artifactSummary.shardsWithArtifacts").value(1))
                .andExpect(jsonPath("$.artifactSummary.mergeHasArtifacts").value(false))
                .andExpect(jsonPath("$.artifactSummary.preferredOutputSource").value("SHARDS"))
                .andExpect(jsonPath("$.merge.exists").value(false))
                .andExpect(jsonPath("$.preferredOutputs", hasSize(1)))
                .andExpect(jsonPath("$.preferredOutputs[0].artifactId").value(artifact.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.preferredOutputs[0].relativePath").value("partial/result.json"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeGroupArtifactResponse(response);
    }

    @Test
    void shouldReturnActivityForShardOnlySucceededGroupWithoutMergeEvents() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroupAt(
                "Activity shard success",
                1,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );
        Worker worker = createApprovedWorker("activity-shard-success");
        WorkExecution shard = createQueuedShardExecutionAt(
                group,
                version,
                0,
                1,
                "Activity shard",
                BASE_TIME.plusSeconds(1)
        );
        assignmentService.assignExecution(
                shard.getId(),
                worker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusSeconds(2)
        );
        lifecycleService.markClaimed(shard.getId(), BASE_TIME.plusSeconds(3));
        lifecycleService.markRunning(shard.getId(), BASE_TIME.plusSeconds(4));
        lifecycleService.markSucceeded(shard.getId(), BASE_TIME.plusSeconds(5));
        ExecutionArtifact artifact = createOutputArtifact(
                shard,
                worker,
                "result.json",
                "result.json",
                BASE_TIME.plusSeconds(6)
        );
        group.markSucceeded(BASE_TIME.plusSeconds(7));
        groupRepository.saveAndFlush(group);

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionGroupId").value(group.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Activity shard success"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.mergeMode").value("NONE"))
                .andExpect(jsonPath("$.failurePolicy").value("FAIL_FAST"))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.events", hasSize(8)))
                .andExpect(jsonPath("$.events[0].type").value("GROUP_CREATED"))
                .andExpect(jsonPath("$.events[0].status").value("CREATED"))
                .andExpect(jsonPath("$.events[1].type").value("SHARD_CREATED"))
                .andExpect(jsonPath("$.events[1].executionId").value(shard.getId().toString()))
                .andExpect(jsonPath("$.events[1].groupRole").value("SHARD"))
                .andExpect(jsonPath("$.events[1].shardIndex").value(0))
                .andExpect(jsonPath("$.events[2].type").value("SHARD_ASSIGNED"))
                .andExpect(jsonPath("$.events[2].workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.events[2].workerHostname").value(worker.getHostname()))
                .andExpect(jsonPath("$.events[3].type").value("SHARD_CLAIMED"))
                .andExpect(jsonPath("$.events[4].type").value("SHARD_RUNNING"))
                .andExpect(jsonPath("$.events[5].type").value("SHARD_SUCCEEDED"))
                .andExpect(jsonPath("$.events[5].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.events[6].type").value("ARTIFACT_UPLOADED"))
                .andExpect(jsonPath("$.events[6].artifactId").value(artifact.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.events[6].relativePath").value("result.json"))
                .andExpect(jsonPath("$.events[7].type").value("GROUP_SUCCEEDED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(eventTypes(response))
                .doesNotContain("MERGE_CREATED", "MERGE_SUCCEEDED");
        assertSafeGroupActivityResponse(response);
    }

    @Test
    void shouldReturnActivityForAgentMergeSucceededGroup() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroupAt(
                "Activity agent merge",
                1,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );
        Worker shardWorker = createApprovedWorker("activity-agent-shard");
        Worker mergeWorker = createApprovedWorker("activity-agent-merge");
        WorkExecution shard = createQueuedShardExecutionAt(
                group,
                version,
                0,
                1,
                "Activity shard",
                BASE_TIME.plusSeconds(1)
        );
        assignmentService.assignExecution(
                shard.getId(),
                shardWorker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusSeconds(2)
        );
        lifecycleService.markClaimed(shard.getId(), BASE_TIME.plusSeconds(3));
        lifecycleService.markRunning(shard.getId(), BASE_TIME.plusSeconds(4));
        lifecycleService.markSucceeded(shard.getId(), BASE_TIME.plusSeconds(5));
        WorkExecution merge = createQueuedMergeExecutionAt(
                group,
                version,
                1,
                "Activity merge",
                BASE_TIME.plusSeconds(10)
        );
        assignmentService.assignExecution(
                merge.getId(),
                mergeWorker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusSeconds(11)
        );
        lifecycleService.markClaimed(merge.getId(), BASE_TIME.plusSeconds(12));
        lifecycleService.markRunning(merge.getId(), BASE_TIME.plusSeconds(13));
        lifecycleService.markSucceeded(merge.getId(), BASE_TIME.plusSeconds(14));
        ExecutionArtifact artifact = createOutputArtifact(
                merge,
                mergeWorker,
                "final-result.json",
                "final-result.json",
                BASE_TIME.plusSeconds(15)
        );
        group.markSucceeded(BASE_TIME.plusSeconds(16));
        groupRepository.saveAndFlush(group);

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.mergeMode").value("AGENT"))
                .andExpect(jsonPath("$.events", hasSize(13)))
                .andExpect(jsonPath("$.events[0].type").value("GROUP_CREATED"))
                .andExpect(jsonPath("$.events[1].type").value("SHARD_CREATED"))
                .andExpect(jsonPath("$.events[5].type").value("SHARD_SUCCEEDED"))
                .andExpect(jsonPath("$.events[6].type").value("MERGE_CREATED"))
                .andExpect(jsonPath("$.events[6].executionId").value(merge.getId().toString()))
                .andExpect(jsonPath("$.events[6].groupRole").value("MERGE"))
                .andExpect(jsonPath("$.events[7].type").value("MERGE_ASSIGNED"))
                .andExpect(jsonPath("$.events[7].workerId").value(mergeWorker.getId().toString()))
                .andExpect(jsonPath("$.events[8].type").value("MERGE_CLAIMED"))
                .andExpect(jsonPath("$.events[9].type").value("MERGE_RUNNING"))
                .andExpect(jsonPath("$.events[10].type").value("MERGE_SUCCEEDED"))
                .andExpect(jsonPath("$.events[11].type").value("ARTIFACT_UPLOADED"))
                .andExpect(jsonPath("$.events[11].artifactId").value(artifact.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.events[11].groupRole").value("MERGE"))
                .andExpect(jsonPath("$.events[11].relativePath").value("final-result.json"))
                .andExpect(jsonPath("$.events[12].type").value("GROUP_SUCCEEDED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(eventTypes(response))
                .contains("SHARD_CREATED", "SHARD_SUCCEEDED", "MERGE_CREATED", "MERGE_SUCCEEDED", "GROUP_SUCCEEDED");
        assertSafeGroupActivityResponse(response);
    }

    @Test
    void shouldReturnActivityForCancelledGroupWithoutMergeEvents() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroupAt(
                "Activity cancelled",
                1,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );
        WorkExecution shard = createQueuedShardExecutionAt(
                group,
                version,
                0,
                1,
                "Cancelled shard",
                BASE_TIME.plusSeconds(1)
        );
        shard.cancelBeforeStart(
                "ADMIN_CANCELLED",
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME.plusSeconds(2)
        );
        executionRepository.saveAndFlush(shard);
        group.markCancelling(
                ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME.plusSeconds(3)
        );
        group.markCancelled(
                ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME.plusSeconds(4)
        );
        groupRepository.saveAndFlush(group);

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.mergeMode").value("AGENT"))
                .andExpect(jsonPath("$.events", hasSize(5)))
                .andExpect(jsonPath("$.events[0].type").value("GROUP_CREATED"))
                .andExpect(jsonPath("$.events[1].type").value("SHARD_CREATED"))
                .andExpect(jsonPath("$.events[2].type").value("SHARD_CANCELLED"))
                .andExpect(jsonPath("$.events[3].type").value("GROUP_CANCELLING"))
                .andExpect(jsonPath("$.events[4].type").value("GROUP_CANCELLED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(eventTypes(response))
                .doesNotContain("MERGE_CREATED", "MERGE_CANCELLED");
        assertSafeGroupActivityResponse(response);
    }

    @Test
    void shouldReturnNotFoundForUnknownExecutionGroupActivity() throws Exception {
        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution group not found."));
    }

    @Test
    void shouldSortActivityEventsDeterministicallyWhenTimestampsTie() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroupAt(
                "Activity ordering",
                2,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );
        Worker worker = createApprovedWorker("activity-ordering");
        WorkExecution shardOne = createQueuedShardExecutionAt(group, version, 1, 2, "Shard one", BASE_TIME);
        WorkExecution merge = createQueuedMergeExecutionAt(group, version, 2, "Merge", BASE_TIME);
        WorkExecution shardZero = createQueuedShardExecutionAt(group, version, 0, 2, "Shard zero", BASE_TIME);
        ExecutionArtifact beta = createOutputArtifact(shardZero, worker, "b.json", "b.json", BASE_TIME);
        ExecutionArtifact finalResult = createOutputArtifact(merge, worker, "final.json", "final.json", BASE_TIME);
        ExecutionArtifact alpha = createOutputArtifact(shardZero, worker, "a.json", "a.json", BASE_TIME);

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(7)))
                .andExpect(jsonPath("$.events[0].type").value("GROUP_CREATED"))
                .andExpect(jsonPath("$.events[1].type").value("SHARD_CREATED"))
                .andExpect(jsonPath("$.events[1].executionId").value(shardZero.getId().toString()))
                .andExpect(jsonPath("$.events[1].shardIndex").value(0))
                .andExpect(jsonPath("$.events[2].type").value("SHARD_CREATED"))
                .andExpect(jsonPath("$.events[2].executionId").value(shardOne.getId().toString()))
                .andExpect(jsonPath("$.events[2].shardIndex").value(1))
                .andExpect(jsonPath("$.events[3].type").value("MERGE_CREATED"))
                .andExpect(jsonPath("$.events[3].executionId").value(merge.getId().toString()))
                .andExpect(jsonPath("$.events[4].type").value("ARTIFACT_UPLOADED"))
                .andExpect(jsonPath("$.events[4].artifactId").value(alpha.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.events[4].relativePath").value("a.json"))
                .andExpect(jsonPath("$.events[5].type").value("ARTIFACT_UPLOADED"))
                .andExpect(jsonPath("$.events[5].artifactId").value(beta.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.events[5].relativePath").value("b.json"))
                .andExpect(jsonPath("$.events[6].type").value("ARTIFACT_UPLOADED"))
                .andExpect(jsonPath("$.events[6].artifactId").value(finalResult.getArtifact().getId().toString()))
                .andExpect(jsonPath("$.events[6].groupRole").value("MERGE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeGroupActivityResponse(response);
    }

    @Test
    void shouldReturnActivityForEmptyGroupWithoutArtifactEvents() throws Exception {
        ExecutionGroup group = createGroupAt(
                "Activity empty",
                1,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );

        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionGroupId").value(group.getId().toString()))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].type").value("GROUP_CREATED"))
                .andExpect(jsonPath("$.events[0].status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(eventTypes(response))
                .doesNotContain("ARTIFACT_UPLOADED", "MERGE_CREATED");
        assertSafeGroupActivityResponse(response);
    }

    @Test
    void shouldReturnNotFoundForUnknownExecutionGroupActivityStream() throws Exception {
        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/activity/stream", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.ALL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution group not found."));
    }

    @Test
    void shouldStreamInitialGroupDetailAndActivitySnapshot() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroupAt(
                "Activity stream initial",
                1,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );
        createQueuedShardExecutionAt(group, version, 0, 1, "Initial stream shard", BASE_TIME.plusSeconds(1));

        String response = activityStreamResponse(group.getId(), "maxEvents", "2");

        assertThat(response)
                .contains("event:group-detail")
                .contains("event:activity-snapshot")
                .contains("\"executionGroupId\":\"" + group.getId() + "\"")
                .contains("\"displayName\":\"Activity stream initial\"")
                .contains("\"events\":[")
                .doesNotContain("event:heartbeat");
        assertThat(countOccurrences(response, "event:group-detail")).isEqualTo(1);
        assertThat(countOccurrences(response, "event:activity-snapshot")).isEqualTo(1);
        assertSafeGroupActivityStreamResponse(response);
    }

    @Test
    void shouldEmitActivityStreamHeartbeatWhenSnapshotsDoNotChange() throws Exception {
        ExecutionGroup group = createGroupAt(
                "Activity stream heartbeat",
                1,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );

        String response = activityStreamResponse(
                group.getId(),
                "pollIntervalMs", "500",
                "heartbeatIntervalMs", "1000",
                "maxEvents", "4"
        );

        assertThat(response)
                .contains("event:group-detail")
                .contains("event:activity-snapshot")
                .contains("event:heartbeat")
                .contains("event:stream-complete")
                .contains("\"reason\":\"MAX_EVENTS_REACHED\"");
        assertThat(countOccurrences(response, "event:group-detail")).isEqualTo(1);
        assertThat(countOccurrences(response, "event:activity-snapshot")).isEqualTo(1);
        assertSafeGroupActivityStreamResponse(response);
    }

    @Test
    void shouldCloseTerminalActivityStreamWhenRequested() throws Exception {
        ExecutionGroup group = createGroupAt(
                "Activity stream terminal",
                1,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                BASE_TIME
        );
        group.markSucceeded(BASE_TIME.plusSeconds(1));
        groupRepository.saveAndFlush(group);

        String response = activityStreamResponse(
                group.getId(),
                "closeOnTerminal", "true",
                "maxEvents", "3"
        );

        assertThat(response)
                .contains("event:group-detail")
                .contains("event:activity-snapshot")
                .contains("event:stream-complete")
                .contains("\"reason\":\"TERMINAL_GROUP_REACHED\"")
                .doesNotContain("event:heartbeat");
        assertSafeGroupActivityStreamResponse(response);
    }

    @Test
    void shouldRejectInvalidActivityStreamParameters() throws Exception {
        ExecutionGroup group = createGroup("Invalid stream params", 1);

        expectBadActivityStream(group, "pollIntervalMs", "499")
                .andExpect(jsonPath("$.message").value("pollIntervalMs must be between 500 and 10000."));
        expectBadActivityStream(group, "pollIntervalMs", "10001")
                .andExpect(jsonPath("$.message").value("pollIntervalMs must be between 500 and 10000."));
        expectBadActivityStream(group, "heartbeatIntervalMs", "999")
                .andExpect(jsonPath("$.message").value("heartbeatIntervalMs must be between 1000 and 60000."));
        expectBadActivityStream(group, "heartbeatIntervalMs", "60001")
                .andExpect(jsonPath("$.message").value("heartbeatIntervalMs must be between 1000 and 60000."));
        expectBadActivityStream(group, "pollIntervalMs", "2000", "heartbeatIntervalMs", "1000")
                .andExpect(jsonPath("$.message")
                        .value("heartbeatIntervalMs must be greater than or equal to pollIntervalMs."));
        expectBadActivityStream(group, "closeOnTerminal", "maybe")
                .andExpect(jsonPath("$.message").value("closeOnTerminal must be true or false."));
        expectBadActivityStream(group, "maxEvents", "0")
                .andExpect(jsonPath("$.message").value("maxEvents must be between 1 and 1000."));
        expectBadActivityStream(group, "maxEvents", "1001")
                .andExpect(jsonPath("$.message").value("maxEvents must be between 1 and 1000."));
    }

    @Test
    void shouldNotMutateRuntimeStateWhenOpeningActivityStream() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Activity stream read only", 1);
        createQueuedShardExecution(group, version, 0, 1, "Read only stream shard");
        long executionCount = executionRepository.count();
        long assignmentCount = assignmentRepository.count();
        long artifactCount = artifactRepository.count();
        long groupCount = groupRepository.count();

        String response = activityStreamResponse(group.getId(), "maxEvents", "2");

        assertSafeGroupActivityStreamResponse(response);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);
    }

    @Test
    void shouldReturnNotFoundForUnknownExecutionGroupArtifacts() throws Exception {
        mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}/artifacts", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution group not found."));
    }

    @Test
    void shouldCancelGroupWithQueuedChildrenAndDefaultReason() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Queued cancel group", 2);
        createQueuedShardExecution(group, version, 0, 2, "Queued first");
        createQueuedShardExecution(group, version, 1, 2, "Queued second");

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.failureCode")
                        .value(ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE))
                .andExpect(jsonPath("$.failureMessage")
                        .value(ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(2))
                .andExpect(jsonPath("$.observability.terminal").value(true))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(false))
                .andExpect(jsonPath("$.observability.canCancel").value(false))
                .andExpect(jsonPath("$.observability.canReconcile").value(false))
                .andExpect(jsonPath("$.observability.shards.cancelled").value(2))
                .andExpect(jsonPath("$.observability.shards.terminal").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(childExecutions(group.getId()))
                .allSatisfy(child -> {
                    assertThat(child.getStatus().name()).isEqualTo("CANCELLED");
                    assertThat(child.getFailureCode()).isEqualTo("ADMIN_CANCELLED");
                    assertThat(child.getFailureMessage())
                            .isEqualTo(ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE);
                });
    }

    @Test
    void shouldCancelGroupWithBlankReasonAndDefaultReason() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Blank reason cancel group", 1);
        createQueuedShardExecution(group, version, 0, 1, "Blank reason shard");

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "   "
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureCode")
                        .value(ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE))
                .andExpect(jsonPath("$.failureMessage")
                        .value(ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(childExecutions(group.getId())).singleElement()
                .satisfies(child -> {
                    assertThat(child.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED);
                    assertThat(child.getFailureMessage())
                            .isEqualTo(ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE);
                });
    }

    @Test
    void shouldCancelAssignedGroupChildrenWithoutDeletingAssignments() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Assigned cancel group", 1);
        Worker worker = createApprovedWorker("assigned-cancel");
        WorkExecution child = createShardExecution(group, version, worker, 0, 1, "Assigned child");
        long assignmentCount = assignmentRepository.count();

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "  Admin requested group stop  "
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureMessage").value("Admin requested group stop"))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(executionRepository.findById(child.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getStatus().name()).isEqualTo("CANCELLED");
                    assertThat(stored.getCompletedAt()).isNotNull();
                    assertThat(stored.getCancelledAt()).isNotNull();
                    assertThat(stored.getFailureCode()).isEqualTo("ADMIN_CANCELLED");
                    assertThat(stored.getFailureMessage()).isEqualTo("Admin requested group stop");
                });
    }

    @Test
    void shouldKeepRunningChildActiveDuringGroupCancelAndFinalizeAfterReport() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Running cancel group", 2);
        WorkerCredentials worker = createApprovedWorkerCredentials("running-cancel");
        WorkExecution runningChild = createShardExecution(group, version, worker.worker(), 0, 2, "Running child");
        WorkExecution queuedChild = createQueuedShardExecution(group, version, 1, 2, "Queued child");
        ClaimedShard claimed = claimNext(worker);
        assertThat(claimed.executionId()).isEqualTo(runningChild.getId());
        reportRunning(worker, claimed);

        String cancellingResponse = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Stop after active child"
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.childExecutionCounts.RUNNING").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1))
                .andExpect(jsonPath("$.observability.terminal").value(false))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(true))
                .andExpect(jsonPath("$.observability.hasActiveChildren").value(true))
                .andExpect(jsonPath("$.observability.canCancel").value(true))
                .andExpect(jsonPath("$.observability.canReconcile").value(true))
                .andExpect(jsonPath("$.observability.shards.running").value(1))
                .andExpect(jsonPath("$.observability.shards.cancelled").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(cancellingResponse);

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.childExecutionCounts.RUNNING").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1));

        reportSucceededAfterRunning(worker, claimed);
        assertGroupCounts(group.getId(), "CANCELLED", 0, 2, 0, 0);
        assertThat(executionRepository.findById(runningChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus().name()).isEqualTo("SUCCEEDED"));
        assertThat(executionRepository.findById(queuedChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus().name()).isEqualTo("CANCELLED"));
    }

    @Test
    void shouldKeepClaimedChildActiveAndCancelQueuedAndAssignedChildrenDuringGroupCancel() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("Claimed cancel group", 3);
        WorkerCredentials claimedWorker = createApprovedWorkerCredentials("claimed-cancel");
        Worker assignedWorker = createApprovedWorker("assigned-during-claimed-cancel");
        WorkExecution claimedChild = createShardExecution(group, version, claimedWorker.worker(), 0, 3, "Claimed child");
        WorkExecution assignedChild = createShardExecution(group, version, assignedWorker, 1, 3, "Assigned child");
        WorkExecution queuedChild = createQueuedShardExecution(group, version, 2, 3, "Queued child");
        ClaimedShard claimed = claimNext(claimedWorker);
        assertThat(claimed.executionId()).isEqualTo(claimedChild.getId());
        long assignmentCount = assignmentRepository.count();

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Stop claimed group"
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.childExecutionCounts.CLAIMED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(2))
                .andExpect(jsonPath("$.observability.hasActiveChildren").value(true))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(true))
                .andExpect(jsonPath("$.lifecycleActions.cancel.available").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.available").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(mergeExecutions(group.getId())).isEmpty();
        assertThat(executionRepository.findById(claimedChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CLAIMED));
        assertThat(executionRepository.findById(assignedChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));
        assertThat(executionRepository.findById(queuedChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));
    }

    @Test
    void shouldAcceptCancelForCancellingGroupWithoutInterruptingActiveChildOrSchedulingWork() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup(
                "Already cancelling group",
                2,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.FAIL_FAST
        );
        WorkerCredentials worker = createApprovedWorkerCredentials("already-cancelling");
        WorkExecution claimedChild = createShardExecution(group, version, worker.worker(), 0, 2, "Claimed child");
        WorkExecution queuedChild = createQueuedShardExecution(group, version, 1, 2, "Queued child");
        ClaimedShard claimed = claimNext(worker);
        assertThat(claimed.executionId()).isEqualTo(claimedChild.getId());
        group.markCancelling(
                ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                "Existing cancellation request.",
                BASE_TIME
        );
        groupRepository.saveAndFlush(group);
        long assignmentCount = assignmentRepository.count();

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.childExecutionCounts.CLAIMED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(true))
                .andExpect(jsonPath("$.lifecycleActions.cancel.available").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.available").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(mergeExecutions(group.getId())).isEmpty();
        assertThat(executionRepository.findById(claimedChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CLAIMED));
        assertThat(executionRepository.findById(queuedChild.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));
    }

    @Test
    void shouldCancelQueuedMergeExecutionWithoutDeletingShardOutputs() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("queued-merge-cancel");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Queued merge cancel",
                1,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        ClaimedShard shard = claimNext(worker);
        reportRunning(worker, shard);
        workerCapabilitiesRepository.deleteById(worker.worker().getId());
        reportSucceededWithOutputAfterRunning(worker, shard, "result.json", "{}");
        long assignmentCount = assignmentRepository.count();
        long artifactCount = artifactRepository.count();
        List<WorkExecution> merges = mergeExecutions(executionGroupId);
        assertThat(merges).hasSize(1);
        assertThat(merges.get(0).getStatus().name()).isEqualTo("QUEUED");

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", executionGroupId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.childExecutionCounts.SUCCEEDED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(mergeExecutions(executionGroupId)).singleElement()
                .satisfies(merge -> assertThat(merge.getStatus().name()).isEqualTo("CANCELLED"));
    }

    @Test
    void shouldNotInterruptRunningMergeAndShouldFinalizeCancelledGroupAfterMergeReport() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("running-merge-cancel");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Running merge cancel",
                1,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        reportSucceededWithOutput(worker, claimNext(worker), "result.json", "{}");
        WorkExecution merge = mergeExecutions(executionGroupId).get(0);
        ClaimedShard mergeClaim = claimNext(worker);
        assertThat(mergeClaim.executionId()).isEqualTo(merge.getId());
        reportRunning(worker, mergeClaim);

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", executionGroupId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.childExecutionCounts.RUNNING").value(1));

        assertThat(executionRepository.findById(merge.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus().name()).isEqualTo("RUNNING"));

        reportFailedAfterRunning(worker, mergeClaim);
        assertGroupCounts(executionGroupId, "CANCELLED", 0, 2, 0, 0);
        assertThat(groupRepository.findById(executionGroupId))
                .hasValueSatisfying(group -> {
                    assertThat(group.getFailureCode())
                            .isEqualTo(ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE);
                    assertThat(group.getFailureMessage())
                            .isEqualTo(ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE);
                });
    }

    @Test
    void shouldValidateGroupCancelRequestAndRejectTerminalGroups() throws Exception {
        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", UUID.randomUUID())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution group not found."));

        ExecutionGroup tooLongReason = createGroup("Too long cancel reason", 1);
        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", tooLongReason.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "%s"
                                }
                                """.formatted("x".repeat(ExecutionGroupCancellationService.MAX_REASON_LENGTH + 1)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "reason must be less than or equal to "
                                + ExecutionGroupCancellationService.MAX_REASON_LENGTH
                                + " characters."
                ));
        assertThat(groupRepository.findById(tooLongReason.getId()))
                .hasValueSatisfying(group -> assertThat(group.getStatus().name()).isEqualTo("CREATED"));

        assertGroupCancelConflict(succeededGroup(), "SUCCEEDED");
        assertGroupCancelConflict(failedGroup(), "FAILED");
        assertGroupCancelConflict(partiallyFailedGroup(), "PARTIALLY_FAILED");
        assertGroupCancelConflict(cancelledGroup(), "CANCELLED");
        assertGroupCancelConflict(expiredGroup(), "EXPIRED");
    }

    @Test
    void shouldKeepStandaloneExecutionCreationAndWorkerClaimReportFlowUnchanged() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        WorkerCredentials credentials = createApprovedWorkerCredentials("standalone-regression");
        WorkExecution execution = createOneOff(version, "Standalone regression");
        assignmentService.assignExecution(
                execution.getId(),
                credentials.worker().getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME
        );

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getExecutionGroupId()).isNull();
                    assertThat(stored.getGroupRole()).isNull();
                    assertThat(stored.getShardIndex()).isNull();
                    assertThat(stored.getShardCount()).isNull();
                });

        String adminListResponse = mockMvc.perform(get("/api/admin/executions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.items[0].executionGroupId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].groupRole").value(nullValue()))
                .andExpect(jsonPath("$.items[0].shardIndex").value(nullValue()))
                .andExpect(jsonPath("$.items[0].shardCount").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(adminListResponse);

        String adminDetailResponse = mockMvc.perform(get("/api/admin/executions/{executionId}", execution.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupMetadata.executionGroupId").value(nullValue()))
                .andExpect(jsonPath("$.groupMetadata.groupRole").value(nullValue()))
                .andExpect(jsonPath("$.groupMetadata.shardIndex").value(nullValue()))
                .andExpect(jsonPath("$.groupMetadata.shardCount").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(adminDetailResponse);

        String claimResponse = mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        credentials.worker().getId()
                )
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(execution.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Standalone regression"))
                .andExpect(jsonPath("$.leaseToken").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String leaseToken = com.jayway.jsonpath.JsonPath.read(claimResponse, "$.leaseToken");
        reportService.reportRunning(
                credentials.worker().getId(),
                execution.getId(),
                leaseToken,
                BASE_TIME.plusSeconds(1)
        );
        reportService.reportSucceeded(
                credentials.worker().getId(),
                execution.getId(),
                leaseToken,
                BASE_TIME.plusSeconds(3)
        );

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(stored -> assertThat(stored.getStatus().name()).isEqualTo("SUCCEEDED"));
    }

    @Test
    void shouldRejectInvalidShardedExecutionGroupCreateRequests() throws Exception {
        WorkDefinitionVersion version = dockerVersion();

        expectBadCreate(shardedCreateRequest(version.getId(), "Invalid shards", 0, "AUTO", null, "FAIL_FAST"))
                .andExpect(jsonPath("$.message").value("shardCount must be greater than 0."));

        expectBadCreate("""
                {
                  "displayName": "Missing command template",
                  "workDefinitionVersionId": "%s",
                  "shardCount": 2,
                  "mergeMode": "NONE",
                  "failurePolicy": "FAIL_FAST",
                  "assignmentMode": "AUTO",
                  "configurationTemplate": {
                    "image": "alpine:3.20",
                    "timeoutSeconds": 30,
                    "resources": {
                      "memoryMb": 128,
                      "cpuCores": 1
                    },
                    "gpu": {
                      "required": false
                    }
                  }
                }
                """.formatted(version.getId()))
                .andExpect(jsonPath("$.message").value("configurationTemplate.commandTemplate is required."));

        expectBadCreate(shardedCreateRequestWithCommandTemplate(
                version.getId(),
                "Unsupported placeholder",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "[\"sh\", \"/workspace/optimize.sh\", \"{{unknown}}\"]"
        )).andExpect(jsonPath("$.message").value("Unsupported commandTemplate placeholder: {{unknown}}"));

        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();

        expectBadCreate(shardedCreateRequest(version.getId(), "Master merge", 2, "AUTO", null, "FAIL_FAST", "MASTER"))
                .andExpect(jsonPath("$.message").value("MASTER mergeMode is designed but not implemented."));

        expectBadCreate(shardedCreateRequest(version.getId(), "Agent merge", 2, "AUTO", null, "FAIL_FAST", "AGENT"))
                .andExpect(jsonPath("$.message").value("mergeConfigurationTemplate is required for AGENT mergeMode."));

        expectBadCreate(shardedCreateRequestWithMergeConfiguration(
                version.getId(),
                "None with merge config",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "NONE",
                baseMergeWorkspace.getId(),
                "[\"sh\", \"/workspace/merge.sh\"]"
        )).andExpect(jsonPath("$.message").value("mergeConfigurationTemplate must be absent when mergeMode is NONE."));

        expectBadCreate(shardedCreateRequest(version.getId(), "Require group", 2, "REQUIRE", null, "FAIL_FAST"))
                .andExpect(jsonPath("$.message").value("REQUIRE assignmentMode is not supported for execution group creation."));
    }

    @Test
    void shouldRejectEmptyCommandTemplateWithoutPersistingGroupOrChildren() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        long groupCount = groupRepository.count();
        long executionCount = executionRepository.count();

        expectBadCreate(shardedCreateRequestWithCommandTemplate(
                version.getId(),
                "Empty command template",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "[]"
        )).andExpect(jsonPath("$.message").value("configurationTemplate.commandTemplate must be a non-empty array."));

        assertThat(groupRepository.count()).isEqualTo(groupCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
    }

    @Test
    void shouldRejectMalformedCommandTemplatePlaceholderWithoutPersistingGroupOrChildren() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        long groupCount = groupRepository.count();
        long executionCount = executionRepository.count();

        expectBadCreate(shardedCreateRequestWithCommandTemplate(
                version.getId(),
                "Malformed placeholder",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "[\"sh\", \"/workspace/optimize.sh\", \"{{shardIndex\", \"{{shardCount}}\"]"
        )).andExpect(jsonPath("$.message").value("Unsupported commandTemplate placeholder."));

        assertThat(groupRepository.count()).isEqualTo(groupCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
    }

    @Test
    void shouldRejectInvalidMergeCommandTemplateWithoutPersistingGroupOrChildren() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        long groupCount = groupRepository.count();
        long executionCount = executionRepository.count();

        expectBadCreate(shardedCreateRequestWithMergeConfiguration(
                version.getId(),
                "Empty merge command template",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "AGENT",
                baseMergeWorkspace.getId(),
                "[]"
        )).andExpect(jsonPath("$.message").value("mergeConfigurationTemplate.commandTemplate must be a non-empty array."));

        expectBadCreate(shardedCreateRequestWithMergeConfiguration(
                version.getId(),
                "Unsupported merge placeholder",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "AGENT",
                baseMergeWorkspace.getId(),
                "[\"sh\", \"/workspace/merge.sh\", \"{{unknown}}\"]"
        )).andExpect(jsonPath("$.message").value("Unsupported commandTemplate placeholder: {{unknown}}"));

        expectBadCreate(shardedCreateRequestWithMergeConfiguration(
                version.getId(),
                "Malformed merge placeholder",
                2,
                "AUTO",
                null,
                "FAIL_FAST",
                "AGENT",
                baseMergeWorkspace.getId(),
                "[\"sh\", \"/workspace/merge.sh\", \"{{shardCount\"]"
        )).andExpect(jsonPath("$.message").value("Unsupported commandTemplate placeholder."));

        assertThat(groupRepository.count()).isEqualTo(groupCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
    }

    @Test
    void shouldCreateShardChildrenWithExpandedCommandsAndScheduleTwoEligibleWorkers() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker firstWorker = createApprovedWorker("initial-first");
        Worker secondWorker = createApprovedWorker("initial-second");
        storeDockerCapabilities(firstWorker);
        storeDockerCapabilities(secondWorker);

        String response = mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shardedCreateRequest(
                                version.getId(),
                                "M18 Sharded Optimization",
                                3,
                                "AUTO",
                                null,
                                "FAIL_FAST"
                        ))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("M18 Sharded Optimization"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.mergeMode").value("NONE"))
                .andExpect(jsonPath("$.failurePolicy").value("FAIL_FAST"))
                .andExpect(jsonPath("$.shardCount").value(3))
                .andExpect(jsonPath("$.totalExecutions").value(3))
                .andExpect(jsonPath("$.activeExecutions").value(2))
                .andExpect(jsonPath("$.terminalExecutions").value(0))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(2))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        UUID executionGroupId = UUID.fromString(JsonPath.read(response, "$.executionGroupId"));

        List<WorkExecution> children = executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId)
                .stream()
                .sorted(Comparator.comparing(WorkExecution::getShardIndex))
                .toList();
        assertThat(children).hasSize(3);
        for (int shardIndex = 0; shardIndex < children.size(); shardIndex++) {
            WorkExecution child = children.get(shardIndex);
            assertThat(child.getExecutionGroupId()).isEqualTo(executionGroupId);
            assertThat(child.getGroupRole().name()).isEqualTo("SHARD");
            assertThat(child.getShardIndex()).isEqualTo(shardIndex);
            assertThat(child.getShardCount()).isEqualTo(3);
            JsonNode configuration = child.getResolvedConfigurationSnapshot();
            assertThat(configuration.has("commandTemplate")).isFalse();
            assertThat(configuration.get("command").get(2).asText()).isEqualTo(Integer.toString(shardIndex));
            assertThat(configuration.get("command").get(3).asText()).isEqualTo("3");
        }

        List<UUID> assignedWorkerIds = assignmentRepository.findByExecution_IdIn(children.stream()
                        .map(WorkExecution::getId)
                        .toList())
                .stream()
                .map(assignment -> assignment.getWorker().getId())
                .toList();
        assertThat(assignedWorkerIds).hasSize(2);
        assertThat(assignedWorkerIds).doesNotHaveDuplicates();
    }

    @Test
    void shouldScheduleOnlyOneShardWithOneEligibleWorkerAndLeaveRestQueued() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker worker = createApprovedWorker("single-worker");
        storeDockerCapabilities(worker);

        String response = mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shardedCreateRequest(version.getId(), "Single worker group", 3, "AUTO", null, "FAIL_FAST"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionGroupId = UUID.fromString(JsonPath.read(response, "$.executionGroupId"));
        assertThat(executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId))
                .extracting(WorkExecution::getStatus)
                .containsExactlyInAnyOrder(
                        dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus.ASSIGNED,
                        dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus.QUEUED,
                        dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus.QUEUED
                );
    }

    @Test
    void shouldPreferRequestedWorkerForFirstShardWhenEligible() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker preferredWorker = createApprovedWorker("preferred");
        Worker fallbackWorker = createApprovedWorker("preferred-fallback");
        storeDockerCapabilities(preferredWorker);
        storeDockerCapabilities(fallbackWorker);

        UUID executionGroupId = createShardedGroup(
                version.getId(),
                "Prefer group",
                2,
                "PREFER",
                preferredWorker.getId(),
                "FAIL_FAST"
        );

        List<WorkExecution> children = executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId)
                .stream()
                .sorted(Comparator.comparing(WorkExecution::getShardIndex))
                .toList();
        assertThat(children).hasSize(2);

        assertThat(assignmentRepository.findByExecution(children.get(0)))
                .hasValueSatisfying(assignment -> {
                    assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.PREFER);
                    assertThat(assignment.getWorker().getId()).isEqualTo(preferredWorker.getId());
                });
        assertThat(assignmentRepository.findByExecution(children.get(1)))
                .hasValueSatisfying(assignment -> {
                    assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.AUTO);
                    assertThat(assignment.getWorker().getId()).isEqualTo(fallbackWorker.getId());
                });
    }

    @Test
    void shouldLeaveAllShardsQueuedWhenNoWorkerIsEligible() throws Exception {
        WorkDefinitionVersion version = dockerVersion();

        String response = mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shardedCreateRequest(version.getId(), "No worker group", 2, "AUTO", null, "FAIL_FAST"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.totalExecutions").value(2))
                .andExpect(jsonPath("$.activeExecutions").value(0))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        UUID executionGroupId = UUID.fromString(JsonPath.read(response, "$.executionGroupId"));
        assertThat(executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId))
                .allSatisfy(child -> assertThat(child.getStatus().name()).isEqualTo("QUEUED"));
    }

    @Test
    void shouldWaveScheduleQueuedShardsAfterTerminalReportsAndSucceedGroup() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        WorkerCredentials worker = createApprovedWorkerCredentials("wave");
        storeDockerCapabilities(worker.worker());

        UUID executionGroupId = createShardedGroup(
                version.getId(),
                "Wave group",
                3,
                "AUTO",
                null,
                "FAIL_FAST"
        );
        assertGroupCounts(executionGroupId, "RUNNING", 1, 0, 1, 2);

        ClaimedShard first = claimNext(worker);
        reportSucceeded(worker, first);
        assertGroupCounts(executionGroupId, "RUNNING", 1, 1, 1, 1);

        ClaimedShard second = claimNext(worker);
        assertThat(second.executionId()).isNotEqualTo(first.executionId());
        reportSucceeded(worker, second);
        assertGroupCounts(executionGroupId, "RUNNING", 1, 2, 1, 0);

        ClaimedShard third = claimNext(worker);
        assertThat(third.executionId()).isNotIn(first.executionId(), second.executionId());
        reportSucceeded(worker, third);
        assertGroupCounts(executionGroupId, "SUCCEEDED", 0, 3, 0, 0);
    }

    @Test
    void shouldDeriveFailedGroupStatusForFailFastPolicy() throws Exception {
        WorkDefinitionVersion failFastVersion = dockerVersion();
        WorkerCredentials failFastWorker = createApprovedWorkerCredentials("fail-fast");
        storeDockerCapabilities(failFastWorker.worker());
        UUID failFastGroupId = createShardedGroup(
                failFastVersion.getId(),
                "Fail fast group",
                2,
                "AUTO",
                null,
                "FAIL_FAST"
        );
        ClaimedWorkerShard failedShard = claimAssignedChild(failFastGroupId, failFastWorker);
        reportFailed(failedShard.worker(), failedShard.claimedShard());
        ClaimedWorkerShard succeededShard = claimAssignedChild(failFastGroupId, failFastWorker);
        reportSucceeded(succeededShard.worker(), succeededShard.claimedShard());
        assertGroupCounts(failFastGroupId, "FAILED", 0, 2, 0, 0);
    }

    @Test
    void shouldDerivePartiallyFailedGroupStatusForAllowPartialPolicy() throws Exception {
        WorkDefinitionVersion partialVersion = dockerVersion();
        WorkerCredentials partialWorker = createApprovedWorkerCredentials("partial");
        storeDockerCapabilities(partialWorker.worker());
        UUID partialGroupId = createShardedGroup(
                partialVersion.getId(),
                "Partial group",
                2,
                "AUTO",
                null,
                "ALLOW_PARTIAL"
        );
        ClaimedWorkerShard failedShard = claimAssignedChild(partialGroupId, partialWorker);
        reportFailed(failedShard.worker(), failedShard.claimedShard());
        ClaimedWorkerShard succeededShard = claimAssignedChild(partialGroupId, partialWorker);
        reportSucceeded(succeededShard.worker(), succeededShard.claimedShard());
        assertGroupCounts(partialGroupId, "PARTIALLY_FAILED", 0, 2, 0, 0);
    }

    @Test
    void shouldCreateAgentMergeGroupWithoutMergeExecutionBeforeShardsFinish() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        Worker worker = createApprovedWorker("agent-initial");
        storeDockerCapabilities(worker);

        String response = mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shardedCreateRequestWithMergeConfiguration(
                                version.getId(),
                                "Agent merge initial",
                                2,
                                "AUTO",
                                null,
                                "FAIL_FAST",
                                "AGENT",
                                baseMergeWorkspace.getId(),
                                "[\"sh\", \"/workspace/merge.sh\", \"{{inputManifestPath}}\"]"
                        ))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.mergeMode").value("AGENT"))
                .andExpect(jsonPath("$.totalExecutions").value(2))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        UUID executionGroupId = UUID.fromString(JsonPath.read(response, "$.executionGroupId"));
        assertThat(mergePlanRepository.findById(executionGroupId)).isPresent();
        assertThat(executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId))
                .hasSize(2)
                .allSatisfy(child -> assertThat(child.getGroupRole().name()).isEqualTo("SHARD"));
    }

    @Test
    void shouldCreateDerivedWorkspaceAndScheduleAgentMergeAfterSuccessfulShards() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("agent-merge-success");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Agent merge success",
                2,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        ClaimedShard first = claimNext(worker);
        reportSucceededWithOutput(worker, first, "result.json", "{\"score\":1}");
        assertThat(mergeExecutions(executionGroupId)).isEmpty();

        ClaimedShard second = claimNext(worker);
        reportSucceededWithOutput(worker, second, "nested/summary.txt", "ok");

        List<WorkExecution> merges = mergeExecutions(executionGroupId);
        assertThat(merges).hasSize(1);
        WorkExecution merge = merges.get(0);
        assertThat(merge.getGroupRole().name()).isEqualTo("MERGE");
        assertThat(merge.getShardIndex()).isNull();
        assertThat(merge.getShardCount()).isEqualTo(2);
        assertThat(merge.getStatus().name()).isEqualTo("ASSIGNED");
        assertThat(merge.getDisplayNameSnapshot()).isEqualTo("Agent merge success merge");

        String detailResponse = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", executionGroupId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGING"))
                .andExpect(jsonPath("$.observability.terminal").value(false))
                .andExpect(jsonPath("$.observability.canCancel").value(true))
                .andExpect(jsonPath("$.observability.canReconcile").value(true))
                .andExpect(jsonPath("$.observability.shards.total").value(2))
                .andExpect(jsonPath("$.observability.shards.succeeded").value(2))
                .andExpect(jsonPath("$.observability.shards.terminal").value(2))
                .andExpect(jsonPath("$.observability.merge.exists").value(true))
                .andExpect(jsonPath("$.observability.merge.executionId").value(merge.getId().toString()))
                .andExpect(jsonPath("$.observability.merge.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.observability.merge.workerId").value(worker.worker().getId().toString()))
                .andExpect(jsonPath("$.observability.merge.workerHostname").value(worker.worker().getHostname()))
                .andExpect(jsonPath("$.observability.merge.total").value(1))
                .andExpect(jsonPath("$.observability.merge.assigned").value(1))
                .andExpect(jsonPath("$.observability.merge.nonTerminal").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(detailResponse);

        JsonNode mergeConfiguration = merge.getResolvedConfigurationSnapshot();
        assertThat(mergeConfiguration.get("command").get(0).asText()).isEqualTo("sh");
        assertThat(mergeConfiguration.get("command").get(2).asText()).isEqualTo("/workspace/inputs/manifest.json");
        UUID derivedWorkspaceId = UUID.fromString(mergeConfiguration.path("workspace").path("artifactId").asText());
        assertThat(derivedWorkspaceId).isNotEqualTo(baseMergeWorkspace.getId());
        assertThat(mergeConfiguration.path("workspace").path("mountPath").asText()).isEqualTo("/workspace");
        assertThat(mergeConfiguration.path("workspace").path("readOnly").asBoolean()).isTrue();

        Artifact derivedWorkspace = artifactRepository.findById(derivedWorkspaceId).orElseThrow();
        Map<String, String> derivedEntries = readZipEntries(derivedWorkspace);
        assertThat(derivedEntries.keySet()).contains(
                "merge.sh",
                "inputs/manifest.json",
                "inputs/shards/0/result.json",
                "inputs/shards/1/nested/summary.txt"
        );
        JsonNode manifest = objectMapper.readTree(derivedEntries.get("inputs/manifest.json"));
        assertThat(manifest.path("executionGroupId").asText()).isEqualTo(executionGroupId.toString());
        assertThat(manifest.path("shardCount").asInt()).isEqualTo(2);
        assertThat(manifest.path("includedShardCount").asInt()).isEqualTo(2);
        assertThat(manifest.toString())
                .contains("/workspace/inputs/shards/0/result.json")
                .contains("/workspace/inputs/shards/1/nested/summary.txt")
                .doesNotContain("storagePath")
                .doesNotContain("lease")
                .doesNotContain("apiKey");

        ClaimedShard mergeClaim = claimNext(worker);
        assertThat(mergeClaim.executionId()).isEqualTo(merge.getId());
        reportSucceeded(worker, mergeClaim);
        assertGroupCounts(executionGroupId, "SUCCEEDED", 0, 3, 0, 0);
    }

    @Test
    void shouldMarkAgentMergeGroupPartiallyFailedWhenPartialShardsAndMergeSucceeded() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("agent-merge-partial");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Agent merge partial",
                2,
                "ALLOW_PARTIAL",
                baseMergeWorkspace.getId()
        );

        reportFailed(worker, claimNext(worker));
        reportSucceededWithOutput(worker, claimNext(worker), "partial.json", "{\"ok\":true}");

        List<WorkExecution> merges = mergeExecutions(executionGroupId);
        assertThat(merges).hasSize(1);
        reportSucceeded(worker, claimNext(worker));
        assertGroupCounts(executionGroupId, "PARTIALLY_FAILED", 0, 3, 0, 0);
    }

    @Test
    void shouldFailAgentMergeGroupWithoutMergeWhenAllowPartialHasNoSuccessfulShards() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("agent-merge-no-success");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Agent merge no successful shards",
                2,
                "ALLOW_PARTIAL",
                baseMergeWorkspace.getId()
        );

        assertThat(mergeExecutions(executionGroupId)).isEmpty();
        assertThat(executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId))
                .hasSize(2)
                .allSatisfy(child -> assertThat(child.getGroupRole().name()).isEqualTo("SHARD"));

        reportFailed(worker, claimNext(worker));
        reportFailed(worker, claimNext(worker));

        String detailResponse = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", executionGroupId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeMode").value("AGENT"))
                .andExpect(jsonPath("$.failurePolicy").value("ALLOW_PARTIAL"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.totalExecutions").value(2))
                .andExpect(jsonPath("$.activeExecutions").value(0))
                .andExpect(jsonPath("$.terminalExecutions").value(2))
                .andExpect(jsonPath("$.childExecutionCounts.FAILED").value(2))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.failureCode").value(notNullValue()))
                .andExpect(jsonPath("$.failureMessage").value(notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(detailResponse);

        String childrenResponse = mockMvc.perform(get(
                        "/api/admin/execution-groups/{executionGroupId}/executions",
                        executionGroupId
                )
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].groupRole").value("SHARD"))
                .andExpect(jsonPath("$[1].groupRole").value("SHARD"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(childrenResponse);
        assertThat(childrenResponse).doesNotContain("\"groupRole\":\"MERGE\"");
        assertThat(mergeExecutions(executionGroupId)).isEmpty();
    }

    @Test
    void shouldFailAgentMergeGroupWithoutMergeForFailFastShardFailure() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("agent-merge-fail-fast");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Agent merge fail fast",
                2,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        reportFailed(worker, claimNext(worker));

        assertGroupCounts(executionGroupId, "FAILED", 0, 1, 0, 1);
        assertThat(mergeExecutions(executionGroupId)).isEmpty();
    }

    @Test
    void shouldExposeTerminalObservabilityForFinishedGroups() throws Exception {
        assertTerminalObservability(succeededGroup(), "SUCCEEDED");
        assertTerminalObservability(failedGroup(), "FAILED");
        assertTerminalObservability(partiallyFailedGroup(), "PARTIALLY_FAILED");
        assertTerminalObservability(cancelledGroup(), "CANCELLED");
        assertTerminalObservability(expiredGroup(), "EXPIRED");
    }

    @Test
    void shouldExposeLifecycleActionsConsistentWithObservabilityForActionableGroupStatuses() throws Exception {
        assertActionableLifecycleActions(createGroup("Created lifecycle actions", 1), "CREATED");
        assertActionableLifecycleActions(groupWithStatus("Scheduling lifecycle actions", ExecutionGroupStatus.SCHEDULING),
                "SCHEDULING");
        assertActionableLifecycleActions(groupWithStatus("Running lifecycle actions", ExecutionGroupStatus.RUNNING),
                "RUNNING");
        assertActionableLifecycleActions(groupWithStatus("Merging lifecycle actions", ExecutionGroupStatus.MERGING),
                "MERGING");
        assertActionableLifecycleActions(groupWithStatus("Cancelling lifecycle actions", ExecutionGroupStatus.CANCELLING),
                "CANCELLING");
    }

    @Test
    void shouldLeaveAgentMergeQueuedWhenNoWorkerIsEligibleForMerge() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("agent-merge-no-worker");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Agent merge no worker",
                1,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        ClaimedShard shard = claimNext(worker);
        reportRunning(worker, shard);
        workerCapabilitiesRepository.deleteById(worker.worker().getId());
        reportSucceededAfterRunning(worker, shard);

        List<WorkExecution> merges = mergeExecutions(executionGroupId);
        assertThat(merges).hasSize(1);
        assertThat(merges.get(0).getStatus().name()).isEqualTo("QUEUED");
        assertGroupCounts(executionGroupId, "MERGING", 0, 1, 0, 1);
    }

    @Test
    void shouldFailAgentMergeGroupWhenMergeExecutionFails() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("agent-merge-failed");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Agent merge failed",
                1,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        reportSucceededWithOutput(worker, claimNext(worker), "result.json", "{}");
        reportFailed(worker, claimNext(worker));

        assertGroupCounts(executionGroupId, "FAILED", 0, 2, 0, 0);
        assertThat(groupRepository.findById(executionGroupId))
                .hasValueSatisfying(group -> assertThat(group.getFailureCode()).isEqualTo("MERGE_EXECUTION_FAILED"));
    }

    @Test
    void shouldReconcileQueuedShardsWithEligibleWorkerAndPreserveOneActiveExecutionPerWorker() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        ExecutionGroup group = createGroup("Reconcile shard group", 2);
        createQueuedShardExecution(group, version, 0, 2, "Reconcile first");
        createQueuedShardExecution(group, version, 1, 2, "Reconcile second");
        Worker worker = createApprovedWorker("reconcile-shards");
        storeDockerCapabilities(worker);

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(assignmentRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(1));
        assertThat(assignmentRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldLeaveQueuedShardsUnchangedWhenReconcileHasNoEligibleWorker() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        ExecutionGroup group = createGroup("Reconcile no worker group", 2);
        createQueuedShardExecution(group, version, 0, 2, "No worker first");
        createQueuedShardExecution(group, version, 1, 2, "No worker second");

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(2));

        assertThat(childExecutions(group.getId()))
                .allSatisfy(child -> assertThat(child.getStatus().name()).isEqualTo("QUEUED"));
    }

    @Test
    void shouldReconcileAgentMergeExactlyOnceAfterShardPhaseCompletes() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Manual merge reconcile",
                2,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );
        Worker worker = createApprovedWorker("manual-merge-reconcile");
        storeDockerCapabilities(worker);

        for (WorkExecution shard : shardExecutions(executionGroupId)) {
            assignmentService.assignExecution(
                    shard.getId(),
                    worker.getId(),
                    ExecutionAssignmentMode.AUTO,
                    BASE_TIME.plusSeconds(shard.getShardIndex())
            );
            lifecycleService.markClaimed(shard.getId(), BASE_TIME.plusSeconds(10 + shard.getShardIndex()));
            lifecycleService.markRunning(shard.getId(), BASE_TIME.plusSeconds(20 + shard.getShardIndex()));
            lifecycleService.markSucceeded(shard.getId(), BASE_TIME.plusSeconds(30 + shard.getShardIndex()));
        }
        assertThat(mergeExecutions(executionGroupId)).isEmpty();

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", executionGroupId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGING"))
                .andExpect(jsonPath("$.childExecutionCounts.SUCCEEDED").value(2))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(response);

        List<WorkExecution> merges = mergeExecutions(executionGroupId);
        assertThat(merges).hasSize(1);
        assertThat(merges.get(0).getStatus().name()).isEqualTo("ASSIGNED");

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", executionGroupId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGING"));
        assertThat(mergeExecutions(executionGroupId)).hasSize(1);
    }

    @Test
    void shouldReconcileQueuedMergeWhenWorkerBecomesEligible() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Artifact baseMergeWorkspace = storeBaseMergeWorkspace();
        WorkerCredentials worker = createApprovedWorkerCredentials("queued-merge-reconcile");
        storeDockerCapabilities(worker.worker());
        UUID executionGroupId = createAgentMergeGroup(
                version.getId(),
                "Queued merge reconcile",
                1,
                "FAIL_FAST",
                baseMergeWorkspace.getId()
        );

        ClaimedShard shard = claimNext(worker);
        reportRunning(worker, shard);
        workerCapabilitiesRepository.deleteById(worker.worker().getId());
        reportSucceededAfterRunning(worker, shard);

        WorkExecution queuedMerge = mergeExecutions(executionGroupId).get(0);
        assertThat(queuedMerge.getStatus().name()).isEqualTo("QUEUED");

        storeDockerCapabilities(worker.worker());
        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", executionGroupId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGING"))
                .andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(1));

        assertThat(executionRepository.findById(queuedMerge.getId()))
                .hasValueSatisfying(merge -> assertThat(merge.getStatus().name()).isEqualTo("ASSIGNED"));
    }

    @Test
    void shouldReconcileTerminalAndCancellingGroupsAsSafeNoOps() throws Exception {
        ExecutionGroup terminal = succeededGroup();
        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", terminal.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup cancelling = createGroup("Manual cancelling reconcile", 1);
        createQueuedShardExecution(cancelling, version, 0, 1, "Cancellable child");
        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", cancelling.getId())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", cancelling.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution group not found."));
    }

    @Test
    void shouldFinalizeCancellingGroupWithoutActiveChildrenOnReconcileWithoutSchedulingOrMerge() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup(
                "Cancelling reconcile without active children",
                1,
                ExecutionGroupMergeMode.AGENT,
                ExecutionGroupFailurePolicy.FAIL_FAST
        );
        WorkExecution cancelledChild = createQueuedShardExecution(group, version, 0, 1, "Cancelled child");
        cancelledChild.cancelBeforeStart(
                "ADMIN_CANCELLED",
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME
        );
        executionRepository.saveAndFlush(cancelledChild);
        group.markCancelling(
                ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME
        );
        groupRepository.saveAndFlush(group);
        long assignmentCount = assignmentRepository.count();

        String response = mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/reconcile", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.childExecutionCounts.CANCELLED").value(1))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(false))
                .andExpect(jsonPath("$.observability.canCancel").value(false))
                .andExpect(jsonPath("$.observability.canReconcile").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.available").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonCode").value("GROUP_ALREADY_CANCELLED"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.available").value(false))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonCode").value("GROUP_ALREADY_CANCELLED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(response);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(mergeExecutions(group.getId())).isEmpty();
        assertThat(executionRepository.findById(cancelledChild.getId()))
                .hasValueSatisfying(child -> assertThat(child.getStatus()).isEqualTo(WorkExecutionStatus.CANCELLED));
    }

    private org.springframework.test.web.servlet.ResultActions expectBadCreate(String request) throws Exception {
        return mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions expectBadActivityStream(ExecutionGroup group,
                                                                                      String... params) throws Exception {
        var requestBuilder = get("/api/admin/execution-groups/{executionGroupId}/activity/stream", group.getId())
                .with(admin())
                .accept(MediaType.ALL);
        for (int index = 0; index < params.length; index += 2) {
            requestBuilder.param(params[index], params[index + 1]);
        }

        return mockMvc.perform(requestBuilder)
                .andExpect(status().isBadRequest());
    }

    private String activityStreamResponse(UUID executionGroupId, String... params) throws Exception {
        var requestBuilder = get("/api/admin/execution-groups/{executionGroupId}/activity/stream", executionGroupId)
                .with(admin())
                .accept(MediaType.TEXT_EVENT_STREAM);
        for (int index = 0; index < params.length; index += 2) {
            requestBuilder.param(params[index], params[index + 1]);
        }

        MvcResult result = mockMvc.perform(requestBuilder)
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5_000L);
            result = mockMvc.perform(asyncDispatch(result))
                    .andReturn();
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(result.getResponse().getContentType()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        return result.getResponse().getContentAsString();
    }

    private UUID createShardedGroup(UUID versionId,
                                    String displayName,
                                    int shardCount,
                                    String assignmentMode,
                                    UUID workerId,
                                    String failurePolicy) throws Exception {
        String response = mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shardedCreateRequest(
                                versionId,
                                displayName,
                                shardCount,
                                assignmentMode,
                                workerId,
                                failurePolicy
                        ))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(response);
        return UUID.fromString(JsonPath.read(response, "$.executionGroupId"));
    }

    private UUID createAgentMergeGroup(UUID versionId,
                                       String displayName,
                                       int shardCount,
                                       String failurePolicy,
                                       UUID baseMergeWorkspaceId) throws Exception {
        String response = mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shardedCreateRequestWithMergeConfiguration(
                                versionId,
                                displayName,
                                shardCount,
                                "AUTO",
                                null,
                                failurePolicy,
                                "AGENT",
                                baseMergeWorkspaceId,
                                "[\"sh\", \"/workspace/merge.sh\", \"{{inputManifestPath}}\", \"{{shardCount}}\"]"
                        ))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(response);
        return UUID.fromString(JsonPath.read(response, "$.executionGroupId"));
    }

    private void assertGroupCounts(UUID executionGroupId,
                                   String expectedStatus,
                                   int activeExecutions,
                                   int terminalExecutions,
                                   int assignedExecutions,
                                   int queuedExecutions) throws Exception {
        var result = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", executionGroupId)
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.activeExecutions").value(activeExecutions))
                .andExpect(jsonPath("$.terminalExecutions").value(terminalExecutions));

        if (assignedExecutions == 0) {
            result.andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").doesNotExist());
        } else {
            result.andExpect(jsonPath("$.childExecutionCounts.ASSIGNED").value(assignedExecutions));
        }
        if (queuedExecutions == 0) {
            result.andExpect(jsonPath("$.childExecutionCounts.QUEUED").doesNotExist());
        } else {
            result.andExpect(jsonPath("$.childExecutionCounts.QUEUED").value(queuedExecutions));
        }
    }

    private void assertTerminalObservability(ExecutionGroup group, String expectedStatus) throws Exception {
        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.observability.terminal").value(true))
                .andExpect(jsonPath("$.observability.cancelInProgress").value(false))
                .andExpect(jsonPath("$.observability.canCancel").value(false))
                .andExpect(jsonPath("$.observability.canReconcile").value(false))
                .andExpect(jsonPath("$.observability.hasActiveChildren").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.available").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonCode")
                        .value(expectedLifecycleUnavailableReasonCode(expectedStatus)))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonMessage")
                        .value(expectedCancelUnavailableReasonMessage(expectedStatus)))
                .andExpect(jsonPath("$.lifecycleActions.cancel.method").value("POST"))
                .andExpect(jsonPath("$.lifecycleActions.cancel.path")
                        .value("/api/admin/execution-groups/{executionGroupId}/cancel"))
                .andExpect(jsonPath("$.lifecycleActions.cancel.requiresBody").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonSupported").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.available").value(false))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonCode")
                        .value(expectedLifecycleUnavailableReasonCode(expectedStatus)))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonMessage")
                        .value(expectedReconcileUnavailableReasonMessage(expectedStatus)))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.method").value("POST"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.path")
                        .value("/api/admin/execution-groups/{executionGroupId}/reconcile"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.requiresBody").value(false))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonSupported").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(response);
    }

    private void assertActionableLifecycleActions(ExecutionGroup group, String expectedStatus) throws Exception {
        String response = mockMvc.perform(get("/api/admin/execution-groups/{executionGroupId}", group.getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.observability.canCancel").value(true))
                .andExpect(jsonPath("$.observability.canReconcile").value(true))
                .andExpect(jsonPath("$.lifecycleActions.cancel.available").value(true))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonMessage").value("Group can be cancelled."))
                .andExpect(jsonPath("$.lifecycleActions.cancel.method").value("POST"))
                .andExpect(jsonPath("$.lifecycleActions.cancel.path")
                        .value("/api/admin/execution-groups/{executionGroupId}/cancel"))
                .andExpect(jsonPath("$.lifecycleActions.cancel.requiresBody").value(false))
                .andExpect(jsonPath("$.lifecycleActions.cancel.reasonSupported").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.available").value(true))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonMessage").value("Group can be reconciled."))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.method").value("POST"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.path")
                        .value("/api/admin/execution-groups/{executionGroupId}/reconcile"))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.requiresBody").value(false))
                .andExpect(jsonPath("$.lifecycleActions.reconcile.reasonSupported").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeAdminResponse(response);
    }

    private static String expectedLifecycleUnavailableReasonCode(String status) {
        return switch (status) {
            case "CANCELLED" -> "GROUP_ALREADY_CANCELLED";
            case "EXPIRED" -> "GROUP_EXPIRED";
            case "SUCCEEDED", "FAILED", "PARTIALLY_FAILED" -> "GROUP_TERMINAL";
            default -> throw new IllegalArgumentException("Unsupported terminal status: " + status);
        };
    }

    private static String expectedCancelUnavailableReasonMessage(String status) {
        return switch (status) {
            case "CANCELLED" -> "Cancelled groups cannot be cancelled again.";
            case "EXPIRED" -> "Expired groups cannot be cancelled.";
            case "SUCCEEDED", "FAILED", "PARTIALLY_FAILED" -> "Terminal groups cannot be cancelled.";
            default -> throw new IllegalArgumentException("Unsupported terminal status: " + status);
        };
    }

    private static String expectedReconcileUnavailableReasonMessage(String status) {
        return switch (status) {
            case "CANCELLED" -> "Cancelled groups do not need reconciliation.";
            case "EXPIRED" -> "Expired groups do not need reconciliation.";
            case "SUCCEEDED", "FAILED", "PARTIALLY_FAILED" -> "Terminal groups do not need reconciliation.";
            default -> throw new IllegalArgumentException("Unsupported terminal status: " + status);
        };
    }

    private void assertGroupCancelConflict(ExecutionGroup group, String statusName) throws Exception {
        ExecutionGroup before = groupRepository.findById(group.getId()).orElseThrow();
        String failureCode = before.getFailureCode();
        String failureMessage = before.getFailureMessage();
        long assignmentCount = assignmentRepository.count();
        long artifactCount = artifactRepository.count();
        List<WorkExecutionStatus> childStatuses = childExecutions(group.getId()).stream()
                .map(WorkExecution::getStatus)
                .toList();

        mockMvc.perform(post("/api/admin/execution-groups/{executionGroupId}/cancel", group.getId())
                .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Cannot cancel execution group from status " + statusName + "."));

        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(childExecutions(group.getId()).stream()
                .map(WorkExecution::getStatus)
                .toList()).isEqualTo(childStatuses);
        assertThat(groupRepository.findById(group.getId()))
                .hasValueSatisfying(after -> {
                    assertThat(after.getStatus().name()).isEqualTo(statusName);
                    assertThat(after.getFailureCode()).isEqualTo(failureCode);
                    assertThat(after.getFailureMessage()).isEqualTo(failureMessage);
                });
    }

    private ClaimedShard claimNext(WorkerCredentials worker) throws Exception {
        String response = mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        worker.worker().getId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(notNullValue()))
                .andExpect(jsonPath("$.leaseToken").value(notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new ClaimedShard(
                UUID.fromString(JsonPath.read(response, "$.executionId")),
                JsonPath.read(response, "$.leaseToken")
        );
    }

    private ClaimedWorkerShard claimAssignedChild(UUID executionGroupId, WorkerCredentials... candidates) throws Exception {
        WorkExecution assignedExecution = childExecutions(executionGroupId).stream()
                .filter(execution -> execution.getStatus() == WorkExecutionStatus.ASSIGNED)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected an ASSIGNED child execution for group " + executionGroupId + "."
                ));
        ExecutionAssignment assignment = assignmentRepository.findByExecution(assignedExecution)
                .orElseThrow(() -> new AssertionError(
                        "Expected assignment for execution " + assignedExecution.getId() + "."
                ));
        WorkerCredentials assignedWorker = List.of(candidates).stream()
                .filter(candidate -> candidate.worker().getId().equals(assignment.getWorker().getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing credentials for assigned worker " + assignment.getWorker().getId() + "."
                ));

        ClaimedShard claimedShard = claimNext(assignedWorker);
        assertThat(claimedShard.executionId()).isEqualTo(assignedExecution.getId());
        return new ClaimedWorkerShard(assignedWorker, claimedShard);
    }

    private void reportSucceeded(WorkerCredentials worker, ClaimedShard claimedShard) throws Exception {
        reportRunning(worker, claimedShard);
        reportSucceededAfterRunning(worker, claimedShard);
    }

    private void reportSucceededWithOutput(WorkerCredentials worker,
                                           ClaimedShard claimedShard,
                                           String relativePath,
                                           String content) throws Exception {
        reportRunning(worker, claimedShard);
        reportSucceededWithOutputAfterRunning(worker, claimedShard, relativePath, content);
    }

    private void reportSucceededWithOutputAfterRunning(WorkerCredentials worker,
                                                       ClaimedShard claimedShard,
                                                       String relativePath,
                                                       String content) throws Exception {
        mockMvc.perform(multipart(
                        "/api/workers/{workerId}/executions/{executionId}/artifacts/output",
                        worker.worker().getId(),
                        claimedShard.executionId()
                )
                        .file(new MockMultipartFile(
                                "file",
                                filename(relativePath),
                                "application/json",
                                content.getBytes(StandardCharsets.UTF_8)
                        ))
                        .param("relativePath", relativePath)
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header("X-EXECUTION-LEASE", claimedShard.leaseToken()))
                .andExpect(status().isOk());
        reportSucceededAfterRunning(worker, claimedShard);
    }

    private void reportSucceededAfterRunning(WorkerCredentials worker, ClaimedShard claimedShard) throws Exception {
        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/succeeded",
                        worker.worker().getId(),
                        claimedShard.executionId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header("X-EXECUTION-LEASE", claimedShard.leaseToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    private void reportFailed(WorkerCredentials worker, ClaimedShard claimedShard) throws Exception {
        reportRunning(worker, claimedShard);
        reportFailedAfterRunning(worker, claimedShard);
    }

    private void reportFailedAfterRunning(WorkerCredentials worker, ClaimedShard claimedShard) throws Exception {
        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/failed",
                        worker.worker().getId(),
                        claimedShard.executionId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header("X-EXECUTION-LEASE", claimedShard.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "failureCode": "TEST_FAILURE",
                                  "failureMessage": "Test failure"
                                }
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private void reportRunning(WorkerCredentials worker, ClaimedShard claimedShard) throws Exception {
        mockMvc.perform(post(
                        "/api/workers/{workerId}/executions/{executionId}/running",
                        worker.worker().getId(),
                        claimedShard.executionId()
                )
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .header("X-EXECUTION-LEASE", claimedShard.leaseToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    private List<WorkExecution> childExecutions(UUID executionGroupId) {
        return executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId);
    }

    private List<WorkExecution> shardExecutions(UUID executionGroupId) {
        return childExecutions(executionGroupId).stream()
                .filter(execution -> execution.getGroupRole() != null
                        && execution.getGroupRole().name().equals("SHARD"))
                .sorted(Comparator.comparing(WorkExecution::getShardIndex))
                .toList();
    }

    private List<WorkExecution> mergeExecutions(UUID executionGroupId) {
        return executionRepository.findAdminExecutionsByExecutionGroupId(executionGroupId)
                .stream()
                .filter(execution -> execution.getGroupRole() != null
                        && execution.getGroupRole().name().equals("MERGE"))
                .toList();
    }

    private String shardedCreateRequest(UUID versionId,
                                        String displayName,
                                        int shardCount,
                                        String assignmentMode,
                                        UUID workerId,
                                        String failurePolicy) {
        return shardedCreateRequest(
                versionId,
                displayName,
                shardCount,
                assignmentMode,
                workerId,
                failurePolicy,
                "NONE"
        );
    }

    private String shardedCreateRequest(UUID versionId,
                                        String displayName,
                                        int shardCount,
                                        String assignmentMode,
                                        UUID workerId,
                                        String failurePolicy,
                                        String mergeMode) {
        return shardedCreateRequest(
                versionId,
                displayName,
                shardCount,
                assignmentMode,
                workerId,
                failurePolicy,
                "[\"sh\", \"/workspace/optimize.sh\", \"{{shardIndex}}\", \"{{shardCount}}\"]",
                mergeMode
        );
    }

    private String shardedCreateRequestWithCommandTemplate(UUID versionId,
                                                           String displayName,
                                                           int shardCount,
                                                           String assignmentMode,
                                                           UUID workerId,
                                                           String failurePolicy,
                                                           String commandTemplate) {
        return shardedCreateRequest(
                versionId,
                displayName,
                shardCount,
                assignmentMode,
                workerId,
                failurePolicy,
                commandTemplate,
                "NONE"
        );
    }

    private String shardedCreateRequestWithMergeConfiguration(UUID versionId,
                                                              String displayName,
                                                              int shardCount,
                                                              String assignmentMode,
                                                              UUID workerId,
                                                              String failurePolicy,
                                                              String mergeMode,
                                                              UUID mergeWorkspaceArtifactId,
                                                              String mergeCommandTemplate) {
        String workerValue = workerId == null ? "null" : "\"" + workerId + "\"";
        return """
                {
                  "displayName": "%s",
                  "workDefinitionVersionId": "%s",
                  "shardCount": %d,
                  "mergeMode": "%s",
                  "failurePolicy": "%s",
                  "assignmentMode": "%s",
                  "workerId": %s,
                  "configurationTemplate": {
                    "image": "alpine:3.20",
                    "commandTemplate": ["sh", "/workspace/optimize.sh", "{{shardIndex}}", "{{shardCount}}"],
                    "timeoutSeconds": 30,
                    "resources": {
                      "memoryMb": 128,
                      "cpuCores": 1
                    },
                    "gpu": {
                      "required": false
                    }
                  },
                  "mergeConfigurationTemplate": {
                    "image": "alpine:3.20",
                    "commandTemplate": %s,
                    "timeoutSeconds": 30,
                    "resources": {
                      "memoryMb": 128,
                      "cpuCores": 1
                    },
                    "gpu": {
                      "required": false
                    },
                    "workspace": {
                      "artifactId": "%s",
                      "mountPath": "/workspace",
                      "readOnly": true
                    }
                  }
                }
                """.formatted(
                displayName,
                versionId,
                shardCount,
                mergeMode,
                failurePolicy,
                assignmentMode,
                workerValue,
                mergeCommandTemplate,
                mergeWorkspaceArtifactId
        );
    }

    private String shardedCreateRequest(UUID versionId,
                                        String displayName,
                                        int shardCount,
                                        String assignmentMode,
                                        UUID workerId,
                                        String failurePolicy,
                                        String commandTemplate,
                                        String mergeMode) {
        String workerValue = workerId == null ? "null" : "\"" + workerId + "\"";
        return """
                {
                  "displayName": "%s",
                  "workDefinitionVersionId": "%s",
                  "shardCount": %d,
                  "mergeMode": "%s",
                  "failurePolicy": "%s",
                  "assignmentMode": "%s",
                  "workerId": %s,
                  "configurationTemplate": {
                    "image": "alpine:3.20",
                    "commandTemplate": %s,
                    "timeoutSeconds": 30,
                    "resources": {
                      "memoryMb": 128,
                      "cpuCores": 1
                    },
                    "gpu": {
                      "required": false
                    }
                  }
                }
                """.formatted(
                displayName,
                versionId,
                shardCount,
                mergeMode,
                failurePolicy,
                assignmentMode,
                workerValue,
                commandTemplate
        );
    }

    private ExecutionGroup createGroup(String displayName, int shardCount) {
        return createGroup(
                displayName,
                shardCount,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST
        );
    }

    private ExecutionGroup createGroup(String displayName,
                                       int shardCount,
                                       ExecutionGroupMergeMode mergeMode,
                                       ExecutionGroupFailurePolicy failurePolicy) {
        return createGroupAt(displayName, shardCount, mergeMode, failurePolicy, LocalDateTime.now());
    }

    private ExecutionGroup createGroupAt(String displayName,
                                         int shardCount,
                                         ExecutionGroupMergeMode mergeMode,
                                         ExecutionGroupFailurePolicy failurePolicy,
                                         LocalDateTime createdAt) {
        return groupRepository.saveAndFlush(ExecutionGroup.create(
                displayName,
                mergeMode,
                failurePolicy,
                shardCount,
                createdAt
        ));
    }

    private ExecutionGroup groupWithStatus(String displayName, ExecutionGroupStatus status) {
        ExecutionGroup group = createGroup(displayName, 1);
        ReflectionTestUtils.setField(group, "status", status);
        ReflectionTestUtils.setField(group, "updatedAt", BASE_TIME);
        return groupRepository.saveAndFlush(group);
    }

    private ExecutionGroup succeededGroup() {
        ExecutionGroup group = createGroup("Succeeded terminal group", 1);
        group.markSucceeded(BASE_TIME);
        return groupRepository.saveAndFlush(group);
    }

    private ExecutionGroup failedGroup() {
        ExecutionGroup group = createGroup("Failed terminal group", 1);
        group.markFailed("TEST_GROUP_FAILED", "Group failed before cancel.", BASE_TIME);
        return groupRepository.saveAndFlush(group);
    }

    private ExecutionGroup partiallyFailedGroup() {
        ExecutionGroup group = createGroup("Partially failed terminal group", 1);
        group.markPartiallyFailed("TEST_GROUP_PARTIAL", "Group partially failed before cancel.", BASE_TIME);
        return groupRepository.saveAndFlush(group);
    }

    private ExecutionGroup cancelledGroup() {
        ExecutionGroup group = createGroup("Cancelled terminal group", 1);
        group.markCancelled(
                ExecutionGroupCancellationService.ADMIN_GROUP_CANCELLED_FAILURE_CODE,
                ExecutionGroupCancellationService.DEFAULT_GROUP_CANCELLATION_MESSAGE,
                BASE_TIME
        );
        return groupRepository.saveAndFlush(group);
    }

    private ExecutionGroup expiredGroup() {
        ExecutionGroup group = createGroup("Expired terminal group", 1);
        ReflectionTestUtils.setField(group, "status", ExecutionGroupStatus.EXPIRED);
        ReflectionTestUtils.setField(group, "updatedAt", BASE_TIME);
        ReflectionTestUtils.setField(group, "completedAt", BASE_TIME);
        ReflectionTestUtils.setField(group, "failureCode", "TEST_GROUP_EXPIRED");
        ReflectionTestUtils.setField(group, "failureMessage", "Group expired before cancel.");
        return groupRepository.saveAndFlush(group);
    }

    private WorkExecution createShardExecution(ExecutionGroup group,
                                               WorkDefinitionVersion version,
                                               Worker worker,
                                               int shardIndex,
                                               int shardCount,
                                               String displayName) {
        WorkExecution execution = createOneOff(version, displayName);
        execution.attachToGroupAsShard(group, shardIndex, shardCount);
        executionRepository.saveAndFlush(execution);
        assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusSeconds(shardIndex)
        );
        return executionRepository.findById(execution.getId()).orElseThrow();
    }

    private WorkExecution createQueuedShardExecution(ExecutionGroup group,
                                                     WorkDefinitionVersion version,
                                                     int shardIndex,
                                                     int shardCount,
                                                     String displayName) {
        WorkExecution execution = createOneOff(version, displayName);
        execution.attachToGroupAsShard(group, shardIndex, shardCount);
        return executionRepository.saveAndFlush(execution);
    }

    private WorkExecution createQueuedShardExecutionAt(ExecutionGroup group,
                                                       WorkDefinitionVersion version,
                                                       int shardIndex,
                                                       int shardCount,
                                                       String displayName,
                                                       LocalDateTime createdAt) {
        WorkExecution execution = WorkExecution.createQueued(
                version,
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                displayName,
                createdAt
        );
        execution.attachToGroupAsShard(group, shardIndex, shardCount);
        return executionRepository.saveAndFlush(execution);
    }

    private WorkExecution createQueuedMergeExecution(ExecutionGroup group,
                                                     WorkDefinitionVersion version,
                                                     Integer shardCount,
                                                     String displayName) {
        WorkExecution execution = createOneOff(version, displayName);
        execution.attachToGroupAsMerge(group, shardCount);
        return executionRepository.saveAndFlush(execution);
    }

    private WorkExecution createQueuedMergeExecutionAt(ExecutionGroup group,
                                                       WorkDefinitionVersion version,
                                                       Integer shardCount,
                                                       String displayName,
                                                       LocalDateTime createdAt) {
        WorkExecution execution = WorkExecution.createQueued(
                version,
                null,
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero(),
                displayName,
                createdAt
        );
        execution.attachToGroupAsMerge(group, shardCount);
        return executionRepository.saveAndFlush(execution);
    }

    private ExecutionArtifact createOutputArtifact(WorkExecution execution,
                                                   Worker worker,
                                                   String relativePath,
                                                   String originalFilename,
                                                   LocalDateTime createdAt) {
        UUID artifactId = UUID.randomUUID();
        Artifact artifact = artifactRepository.save(Artifact.create(
                artifactId,
                ArtifactKind.EXECUTION_OUTPUT,
                originalFilename,
                "application/json",
                relativePath.length(),
                "a".repeat(64),
                artifactId + "/artifact",
                createdAt,
                worker.getId().toString()
        ));
        return executionArtifactRepository.saveAndFlush(ExecutionArtifact.create(
                execution,
                artifact,
                worker,
                relativePath,
                createdAt
        ));
    }

    private WorkExecution createOneOff(WorkDefinitionVersion version, String displayName) {
        return creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                JsonNodeFactory.instance.objectNode(),
                null,
                displayName
        ));
    }

    private WorkDefinitionVersion noOpVersion() {
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive.execution-group-api-" + UUID.randomUUID(),
                WorkType.TASK,
                "NO_OP",
                null,
                "localhive.no-op",
                1,
                JsonNodeFactory.instance.objectNode().put("message", "noop"),
                ResourceRequest.zero(),
                adminUser.getId()
        ));
    }

    private WorkDefinitionVersion dockerVersion() {
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive.execution-group-docker-" + UUID.randomUUID(),
                WorkType.TASK,
                "Docker Workload",
                null,
                "localhive.docker.workload",
                1,
                dockerBaseConfiguration(),
                ResourceRequest.of(128, 1, false),
                adminUser.getId()
        ));
    }

    private Artifact storeBaseMergeWorkspace() throws IOException {
        return artifactManagementService.storeWorkspacePackage(
                new MockMultipartFile(
                        "file",
                        "merge-workspace.zip",
                        "application/zip",
                        zip(Map.of(
                                "merge.sh",
                                "#!/bin/sh\ncat /workspace/inputs/manifest.json > /output/manifest.json\n"
                        ))
                ),
                adminUser.getId().toString()
        );
    }

    private Map<String, String> readZipEntries(Artifact artifact) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zipInput = new ZipInputStream(
                Files.newInputStream(artifactStorageService.resolveReadablePath(artifact))
        )) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(
                            entry.getName(),
                            new String(zipInput.readAllBytes(), StandardCharsets.UTF_8)
                    );
                }
                zipInput.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zipOutput.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutput.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zipOutput.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String filename(String relativePath) {
        int separator = relativePath.lastIndexOf('/');
        return separator < 0 ? relativePath : relativePath.substring(separator + 1);
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

    private Worker createApprovedWorker(String suffix) {
        return createApprovedWorkerCredentials(suffix).worker();
    }

    private void storeDockerCapabilities(Worker worker) {
        WorkerCapabilities capabilities = WorkerCapabilities.create(worker);
        capabilities.replaceWith(
                BASE_TIME,
                executors(executor("localhive.docker.workload")),
                true,
                textArray(List.of("alpine:3.20")),
                4096,
                8,
                false
        );
        workerCapabilitiesRepository.save(capabilities);
    }

    private static ObjectNode executor(String executorId) {
        return JsonNodeFactory.instance.objectNode()
                .put("executorId", executorId)
                .put("executorContractVersion", 1)
                .put("enabled", true);
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

    private WorkerCredentials createApprovedWorkerCredentials(String suffix) {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m17-worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .lastHeartbeatAt(LocalDateTime.now())
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

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private static void assertSafeAdminResponse(String response) {
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
                .doesNotContain("C:\\secret")
                .doesNotContain("Exception")
                .doesNotContain("StackTrace");
    }

    private static void assertSafeGroupArtifactResponse(String response) {
        assertSafeAdminResponse(response);
        assertThat(response)
                .doesNotContain("sha256")
                .doesNotContain("uploadedByWorkerId")
                .doesNotContain("createdBy")
                .doesNotContain("storage_path")
                .doesNotContain("target/test-artifacts")
                .doesNotContain("mergeConfigurationTemplate")
                .doesNotContain("mergePlan");
    }

    private static void assertSafeGroupActivityResponse(String response) {
        assertSafeAdminResponse(response);
        assertThat(response)
                .doesNotContain("sha256")
                .doesNotContain("uploadedByWorkerId")
                .doesNotContain("createdBy")
                .doesNotContain("storage_path")
                .doesNotContain("target/test-artifacts")
                .doesNotContain("mergeConfigurationTemplate")
                .doesNotContain("mergePlan");
    }

    private static void assertSafeGroupActivityStreamResponse(String response) {
        assertSafeGroupActivityResponse(response);
        assertThat(response)
                .doesNotContain("lease")
                .doesNotContain("token")
                .doesNotContain("secret")
                .doesNotContain("artifact/")
                .doesNotContain("$OUTPUT_DIR");
    }

    private static List<String> eventTypes(String response) {
        return JsonPath.read(response, "$.events[*].type");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }

        return count;
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }

    private record ClaimedShard(UUID executionId, String leaseToken) {
    }

    private record ClaimedWorkerShard(WorkerCredentials worker, ClaimedShard claimedShard) {
    }
}
