package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionGroupRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
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
    private ExecutionGroupRepository groupRepository;

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

        mockMvc.perform(post("/api/admin/execution-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
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

        mockMvc.perform(post("/api/admin/execution-groups")
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
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

        mockMvc.perform(post("/api/admin/execution-groups")
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
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

        mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionGroupId").exists());
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
        ExecutionGroup group = createGroup("Children group", 2);
        Worker firstWorker = createApprovedWorker("children-first");
        Worker secondWorker = createApprovedWorker("children-second");
        WorkExecution firstChild = createShardExecution(group, version, firstWorker, 0, 2, "First child");
        Thread.sleep(5);
        WorkExecution secondChild = createShardExecution(group, version, secondWorker, 1, 2, "Second child");
        WorkExecution standalone = createOneOff(version, "Standalone execution");

        String response = mockMvc.perform(get(
                        "/api/admin/execution-groups/{executionGroupId}/executions",
                        group.getId()
                )
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].executionId").value(secondChild.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].assignmentMode").value("REQUIRE"))
                .andExpect(jsonPath("$[0].workerId").value(secondWorker.getId().toString()))
                .andExpect(jsonPath("$[0].workerHostname").value(secondWorker.getHostname()))
                .andExpect(jsonPath("$[0].groupRole").value("SHARD"))
                .andExpect(jsonPath("$[0].shardIndex").value(1))
                .andExpect(jsonPath("$[0].shardCount").value(2))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].executionId").value(firstChild.getId().toString()))
                .andExpect(jsonPath("$[1].shardIndex").value(0))
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
                .andExpect(jsonPath("$.groupMetadata.shardCount").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminResponse(adminExecutionDetailResponse);
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

        expectBadCreate(shardedCreateRequest(version.getId(), "Master merge", 2, "AUTO", null, "FAIL_FAST", "MASTER"))
                .andExpect(jsonPath("$.message").value("Merge modes MASTER and AGENT are designed but not implemented in M18."));

        expectBadCreate(shardedCreateRequest(version.getId(), "Agent merge", 2, "AUTO", null, "FAIL_FAST", "AGENT"))
                .andExpect(jsonPath("$.message").value("Merge modes MASTER and AGENT are designed but not implemented in M18."));

        expectBadCreate(shardedCreateRequest(version.getId(), "Require group", 2, "REQUIRE", null, "FAIL_FAST"))
                .andExpect(jsonPath("$.message").value("REQUIRE assignmentMode is not supported for execution group creation in M18."));
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
        reportFailed(failFastWorker, claimNext(failFastWorker));
        reportSucceeded(failFastWorker, claimNext(failFastWorker));
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
        reportFailed(partialWorker, claimNext(partialWorker));
        reportSucceeded(partialWorker, claimNext(partialWorker));
        assertGroupCounts(partialGroupId, "PARTIALLY_FAILED", 0, 2, 0, 0);
    }

    private org.springframework.test.web.servlet.ResultActions expectBadCreate(String request) throws Exception {
        return mockMvc.perform(post("/api/admin/execution-groups")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
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

    private void reportSucceeded(WorkerCredentials worker, ClaimedShard claimedShard) throws Exception {
        reportRunning(worker, claimedShard);
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
        return groupRepository.save(ExecutionGroup.create(
                displayName,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                shardCount,
                LocalDateTime.now()
        ));
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

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }

    private record ClaimedShard(UUID executionId, String leaseToken) {
    }
}
