package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecutionDisplayName;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminCreateExecutionControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m10-admin";
    private static final String CREATE_EXECUTION_PATH = "/api/admin/executions";

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
    private UserRepository userRepository;

    private User adminUser;

    @BeforeEach
    void resetDatabase() {
        executionArtifactRepository.deleteAll();
        artifactRepository.deleteAll();
        attemptRepository.deleteAll();
        assignmentRepository.deleteAll();
        executionRepository.deleteAll();
        instanceRepository.deleteAll();
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser(ADMIN_USERNAME);
    }

    @Test
    void shouldCreateNoOpExecutionAssignedToApprovedWorkerAndAllowClaim() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        WorkerCredentials credentials = createWorkerCredentials(
                "no-op",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );

        String response = mockMvc.perform(adminCreate("""
                        {
                          "workDefinitionVersionId": "%s",
                          "workerId": "%s",
                          "assignmentMode": "REQUIRE",
                          "displayName": "   ",
                          "configuration": {}
                        }
                        """.formatted(version.getId(), credentials.worker().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("NO-OP smoke test"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.workDefinitionVersionId").value(version.getId().toString()))
                .andExpect(jsonPath("$.workDefinitionLogicalId").value("localhive.no-op"))
                .andExpect(jsonPath("$.workDefinitionVersion").value(1))
                .andExpect(jsonPath("$.executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.assignment.workerId").value(credentials.worker().getId().toString()))
                .andExpect(jsonPath("$.assignment.workerHostname").value(credentials.worker().getHostname()))
                .andExpect(jsonPath("$.assignment.mode").value("REQUIRE"))
                .andExpect(jsonPath("$.assignment.assignedAt").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionId = UUID.fromString(JsonPath.read(response, "$.executionId"));
        assertSafeCreateResponse(response);
        assertAssignedExecution(executionId, credentials.worker(), "NO-OP smoke test");

        mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        credentials.worker().getId()
                )
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.displayName").value("NO-OP smoke test"))
                .andExpect(jsonPath("$.executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.leaseToken").exists());
    }

    @Test
    void shouldCreateDockerWorkloadExecutionAssignedToApprovedWorkerAndAllowClaim() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        WorkerCredentials credentials = createWorkerCredentials(
                "docker",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );

        String response = mockMvc.perform(adminCreate(dockerCreateRequest(
                        version.getId(),
                        credentials.worker().getId(),
                        "M10 Docker Smoke",
                        "echo m10-admin-create > /output/m10.txt"
                )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionId").exists())
                .andExpect(jsonPath("$.displayName").value("M10 Docker Smoke"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.workDefinitionVersionId").value(version.getId().toString()))
                .andExpect(jsonPath("$.workDefinitionLogicalId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.workDefinitionVersion").value(1))
                .andExpect(jsonPath("$.executorId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.assignment.workerId").value(credentials.worker().getId().toString()))
                .andExpect(jsonPath("$.assignment.workerHostname").value(credentials.worker().getHostname()))
                .andExpect(jsonPath("$.assignment.mode").value("REQUIRE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionId = UUID.fromString(JsonPath.read(response, "$.executionId"));
        assertSafeCreateResponse(response);
        assertAssignedExecution(executionId, credentials.worker(), "M10 Docker Smoke");
        assertThat(executionRepository.findById(executionId))
                .hasValueSatisfying(execution -> {
                    JsonNode configuration = execution.getResolvedConfigurationSnapshot();
                    assertThat(configuration.get("image").asText()).isEqualTo("alpine:3.20");
                    assertThat(configuration.get("command").get(2).asText())
                            .isEqualTo("echo m10-admin-create > /output/m10.txt");
                    assertThat(configuration.get("timeoutSeconds").asInt()).isEqualTo(30);
                    assertThat(configuration.get("resources").get("memoryMb").asInt()).isEqualTo(128);
                    assertThat(configuration.get("resources").get("cpuCores").asInt()).isEqualTo(1);
                    assertThat(configuration.get("gpu").get("required").asBoolean()).isFalse();
                    assertThat(configuration.has("workspace")).isFalse();
                    assertThat(execution.getResolvedResourceRequest().getRequiredRamMb()).isEqualTo(128);
                    assertThat(execution.getResolvedResourceRequest().getRequiredCpuCores()).isEqualTo(1);
                    assertThat(execution.getResolvedResourceRequest().isGpuRequired()).isFalse();
                });

        mockMvc.perform(post(
                        "/api/workers/{workerId}/assigned-executions/claim-next",
                        credentials.worker().getId()
                )
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.displayName").value("M10 Docker Smoke"))
                .andExpect(jsonPath("$.executorId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.configuration.image").value("alpine:3.20"))
                .andExpect(jsonPath("$.configuration.command[2]").value("echo m10-admin-create > /output/m10.txt"));
    }

    @Test
    void shouldCreateDockerWorkloadExecutionWithWorkspaceArtifactReference() throws Exception {
        WorkDefinitionVersion version = dockerVersion();
        Worker worker = createApprovedWorker("workspace");
        Artifact workspaceArtifact = createArtifact(ArtifactKind.WORKSPACE_PACKAGE, "C:\\secret\\workspace.zip");

        String response = mockMvc.perform(adminCreate("""
                        {
                          "workDefinitionVersionId": "%s",
                          "workerId": "%s",
                          "displayName": "Workspace execution",
                          "configuration": {
                            "image": "alpine:3.20",
                            "command": ["sh", "-c", "ls -la /workspace"],
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
                        """.formatted(version.getId(), worker.getId(), workspaceArtifact.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Workspace execution"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionId = UUID.fromString(JsonPath.read(response, "$.executionId"));
        assertSafeCreateResponse(response);
        assertThat(response).doesNotContain("C:\\secret");
        assertThat(executionRepository.findById(executionId))
                .hasValueSatisfying(execution -> {
                    JsonNode workspace = execution.getResolvedConfigurationSnapshot().get("workspace");
                    assertThat(workspace.get("artifactId").asText()).isEqualTo(workspaceArtifact.getId().toString());
                    assertThat(workspace.get("mountPath").asText()).isEqualTo("/workspace");
                    assertThat(workspace.get("readOnly").asBoolean()).isTrue();
                });
    }

    @Test
    void shouldAllowApprovedOfflineAndPausedWorkersAtCreation() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        Worker offlineWorker = createWorker(
                "offline",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.OFFLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        Worker pausedWorker = createWorker(
                "paused",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.PAUSED
        );

        mockMvc.perform(adminCreate(noOpCreateRequest(version.getId(), offlineWorker.getId(), "Offline worker task")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignment.workerId").value(offlineWorker.getId().toString()));

        mockMvc.perform(adminCreate(noOpCreateRequest(version.getId(), pausedWorker.getId(), "Paused worker task")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignment.workerId").value(pausedWorker.getId().toString()));
    }

    @Test
    void shouldRejectInvalidAdminCreateExecutionRequests() throws Exception {
        WorkDefinitionVersion noOpVersion = noOpVersion();
        WorkDefinitionVersion dockerVersion = dockerVersion();
        Worker approvedWorker = createApprovedWorker("validation");
        Worker pendingWorker = createWorker(
                "pending",
                WorkerApprovalStatus.PENDING,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        Artifact workspaceArtifact = createArtifact(ArtifactKind.WORKSPACE_PACKAGE, "workspace/artifact");
        Artifact outputArtifact = createArtifact(ArtifactKind.EXECUTION_OUTPUT, "output/artifact");
        WorkDefinitionVersion workloadVersion = createLocalDefinition(
                "localhive.unsupported-workload",
                WorkType.WORKLOAD,
                "Unsupported workload",
                "localhive.no-op",
                noOpConfiguration(),
                ResourceRequest.zero()
        );
        WorkDefinitionVersion unsupportedExecutorVersion = createLocalDefinition(
                "localhive.unsupported-task",
                WorkType.TASK,
                "Unsupported task",
                "localhive.unsupported",
                JsonNodeFactory.instance.objectNode(),
                ResourceRequest.zero()
        );
        WorkDefinitionVersion pendingVersion = definitionManagementService.createImportedDefinition(
                definitionCommand(
                        "adrian.pending-task",
                        WorkType.TASK,
                        "Pending task",
                        "localhive.no-op",
                        noOpConfiguration(),
                        ResourceRequest.zero()
                ),
                "external-pending"
        );
        WorkDefinitionVersion rejectedVersion = definitionManagementService.createImportedDefinition(
                definitionCommand(
                        "adrian.rejected-task",
                        WorkType.TASK,
                        "Rejected task",
                        "localhive.no-op",
                        noOpConfiguration(),
                        ResourceRequest.zero()
                ),
                "external-rejected"
        );
        definitionManagementService.rejectImportedPendingVersion(
                "adrian.rejected-task",
                1,
                adminUser.getId()
        );

        expectBadRequest("""
                {
                  "workerId": "%s",
                  "configuration": {}
                }
                """.formatted(approvedWorker.getId()));

        expectNotFound(noOpCreateRequest(UUID.randomUUID(), approvedWorker.getId(), "Unknown version"));

        expectBadRequest("""
                {
                  "workDefinitionVersionId": "%s",
                  "configuration": {}
                }
                """.formatted(noOpVersion.getId()));

        expectNotFound(noOpCreateRequest(noOpVersion.getId(), UUID.randomUUID(), "Unknown worker"));
        expectBadRequest(noOpCreateRequest(noOpVersion.getId(), pendingWorker.getId(), "Pending worker"));
        expectBadRequest(noOpCreateRequestWithMode(noOpVersion.getId(), approvedWorker.getId(), "AUTO"));
        expectBadRequest(noOpCreateRequestWithMode(noOpVersion.getId(), approvedWorker.getId(), "PREFER"));
        expectBadRequest(noOpCreateRequest(
                noOpVersion.getId(),
                approvedWorker.getId(),
                "x".repeat(WorkExecutionDisplayName.MAX_LENGTH + 1)
        ));
        expectBadRequest(noOpCreateRequest(workloadVersion.getId(), approvedWorker.getId(), "Workload"));
        expectBadRequest(noOpCreateRequest(unsupportedExecutorVersion.getId(), approvedWorker.getId(), "Unsupported"));
        expectBadRequest(noOpCreateRequest(pendingVersion.getId(), approvedWorker.getId(), "Pending version"));
        expectBadRequest(noOpCreateRequest(rejectedVersion.getId(), approvedWorker.getId(), "Rejected version"));
        expectBadRequest(noOpCreateRequestWithConfiguration(
                noOpVersion.getId(),
                approvedWorker.getId(),
                """
                        {
                          "message": "noop",
                          "apiKey": "must-not-pass"
                        }
                        """
        ));

        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration("ubuntu:24.04", "[\"sh\", \"-c\", \"echo bad\"]", 30, 128, 1, false, null)
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration("alpine:3.20", "[\"sh\", \"   \"]", 30, 128, 1, false, null)
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration("alpine:3.20", "[\"sh\", \"-c\", \"echo bad\"]", 0, 128, 1, false, null)
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration("alpine:3.20", "[\"sh\", \"-c\", \"echo bad\"]", 30, 15, 1, false, null)
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration("alpine:3.20", "[\"sh\", \"-c\", \"echo bad\"]", 30, 128, 9, false, null)
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration("alpine:3.20", "[\"sh\", \"-c\", \"echo bad\"]", 30, 128, 1, true, null)
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration(
                        "alpine:3.20",
                        "[\"sh\", \"-c\", \"echo bad\"]",
                        30,
                        128,
                        1,
                        false,
                        """
                                {
                                  "artifactId": "%s",
                                  "mountPath": "/tmp",
                                  "readOnly": true
                                }
                                """.formatted(workspaceArtifact.getId())
                )
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration(
                        "alpine:3.20",
                        "[\"sh\", \"-c\", \"echo bad\"]",
                        30,
                        128,
                        1,
                        false,
                        """
                                {
                                  "artifactId": "%s",
                                  "mountPath": "/workspace",
                                  "readOnly": false
                                }
                                """.formatted(workspaceArtifact.getId())
                )
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration(
                        "alpine:3.20",
                        "[\"sh\", \"-c\", \"echo bad\"]",
                        30,
                        128,
                        1,
                        false,
                        """
                                {
                                  "artifactId": "%s",
                                  "mountPath": "/workspace",
                                  "readOnly": true
                                }
                                """.formatted(UUID.randomUUID())
                )
        ));
        expectBadRequest(dockerCreateRequestWithConfiguration(
                dockerVersion.getId(),
                approvedWorker.getId(),
                dockerConfiguration(
                        "alpine:3.20",
                        "[\"sh\", \"-c\", \"echo bad\"]",
                        30,
                        128,
                        1,
                        false,
                        """
                                {
                                  "artifactId": "%s",
                                  "mountPath": "/workspace",
                                  "readOnly": true
                                }
                                """.formatted(outputArtifact.getId())
                )
        ));
    }

    @Test
    void shouldEnforceAdminSecurityAndKeepCreateResponseSafe() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        WorkerCredentials credentials = createWorkerCredentials(
                "security",
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
        String body = noOpCreateRequest(version.getId(), credentials.worker().getId(), "Security task");

        mockMvc.perform(post(CREATE_EXECUTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post(CREATE_EXECUTION_PATH)
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(post(CREATE_EXECUTION_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        String response = mockMvc.perform(adminCreate(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.leaseExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.executorConfiguration").doesNotExist())
                .andExpect(jsonPath("$.requestedConfigurationSnapshot").doesNotExist())
                .andExpect(jsonPath("$.resolvedConfigurationSnapshot").doesNotExist())
                .andExpect(jsonPath("$.storagePath").doesNotExist())
                .andExpect(jsonPath("$.dataRoot").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeCreateResponse(response);
        assertThat(response).contains("Security task").contains("displayName");
    }

    private MockHttpServletRequestBuilder adminCreate(String body) {
        return post(CREATE_EXECUTION_PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void expectBadRequest(String body) throws Exception {
        mockMvc.perform(adminCreate(body))
                .andExpect(status().isBadRequest());
    }

    private void expectNotFound(String body) throws Exception {
        mockMvc.perform(adminCreate(body))
                .andExpect(status().isNotFound());
    }

    private void assertAssignedExecution(UUID executionId, Worker worker, String displayName) {
        assertThat(executionRepository.findById(executionId))
                .hasValueSatisfying(execution -> {
                    assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED);
                    assertThat(execution.getDisplayNameSnapshot()).isEqualTo(displayName);
                    assertThat(assignmentRepository.findByExecution(execution))
                            .hasValueSatisfying(assignment -> {
                                assertThat(assignment.getExecution().getId()).isEqualTo(executionId);
                                assertThat(assignment.getWorker().getId()).isEqualTo(worker.getId());
                                assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.REQUIRE);
                                assertThat(assignment.getLeaseTokenHash()).isNull();
                                assertThat(assignment.getLeaseExpiresAt()).isNull();
                            });
                });
    }

    private String noOpCreateRequest(UUID versionId, UUID workerId, String displayName) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "displayName": "%s",
                  "configuration": {}
                }
                """.formatted(versionId, workerId, displayName);
    }

    private String noOpCreateRequestWithMode(UUID versionId, UUID workerId, String assignmentMode) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "%s",
                  "configuration": {}
                }
                """.formatted(versionId, workerId, assignmentMode);
    }

    private String noOpCreateRequestWithConfiguration(UUID versionId, UUID workerId, String configuration) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "configuration": %s
                }
                """.formatted(versionId, workerId, configuration);
    }

    private String dockerCreateRequest(UUID versionId, UUID workerId, String displayName, String command) {
        return dockerCreateRequestWithConfiguration(
                versionId,
                workerId,
                dockerConfiguration(
                        "alpine:3.20",
                        "[\"sh\", \"-c\", \"%s\"]".formatted(command),
                        30,
                        128,
                        1,
                        false,
                        null
                ),
                displayName
        );
    }

    private String dockerCreateRequestWithConfiguration(UUID versionId, UUID workerId, String configuration) {
        return dockerCreateRequestWithConfiguration(versionId, workerId, configuration, "Docker validation");
    }

    private String dockerCreateRequestWithConfiguration(UUID versionId,
                                                       UUID workerId,
                                                       String configuration,
                                                       String displayName) {
        return """
                {
                  "workDefinitionVersionId": "%s",
                  "workerId": "%s",
                  "assignmentMode": "REQUIRE",
                  "displayName": "%s",
                  "configuration": %s
                }
                """.formatted(versionId, workerId, displayName, configuration);
    }

    private String dockerConfiguration(String image,
                                       String command,
                                       int timeoutSeconds,
                                       int memoryMb,
                                       int cpuCores,
                                       boolean gpuRequired,
                                       String workspace) {
        String workspaceSection = workspace == null ? "" : ",\n    \"workspace\": " + workspace;
        return """
                {
                  "image": "%s",
                  "command": %s,
                  "timeoutSeconds": %d,
                  "resources": {
                    "memoryMb": %d,
                    "cpuCores": %d
                  },
                  "gpu": {
                    "required": %s
                  }%s
                }
                """.formatted(image, command, timeoutSeconds, memoryMb, cpuCores, gpuRequired, workspaceSection);
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
                                                        JsonNode configuration,
                                                        ResourceRequest defaultResourceRequest) {
        return definitionManagementService.createLocalDefinition(definitionCommand(
                logicalIdentifier,
                workType,
                name,
                executorId,
                configuration,
                defaultResourceRequest
        ));
    }

    private DefinitionContentCommand definitionCommand(String logicalIdentifier,
                                                       WorkType workType,
                                                       String name,
                                                       String executorId,
                                                       JsonNode configuration,
                                                       ResourceRequest defaultResourceRequest) {
        return new DefinitionContentCommand(
                logicalIdentifier,
                workType,
                name,
                null,
                executorId,
                1,
                configuration,
                defaultResourceRequest,
                adminUser.getId()
        );
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

    private Artifact createArtifact(ArtifactKind kind, String storagePath) {
        UUID artifactId = UUID.randomUUID();
        return artifactRepository.save(Artifact.create(
                artifactId,
                kind,
                "artifact.zip",
                "application/zip",
                128L,
                "a".repeat(64),
                storagePath,
                LocalDateTime.now(),
                "m10-test"
        ));
    }

    private Worker createApprovedWorker(String suffix) {
        return createWorker(
                suffix,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
    }

    private WorkerCredentials createWorkerCredentials(String suffix,
                                                      WorkerApprovalStatus approvalStatus,
                                                      WorkerConnectionStatus connectionStatus,
                                                      WorkerAvailabilityStatus availabilityStatus) {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = createWorker(suffix, approvalStatus, connectionStatus, availabilityStatus);
        worker.setApiKeyHash(passwordEncoder.encode(rawApiKey));
        return new WorkerCredentials(workerRepository.save(worker), rawApiKey);
    }

    private Worker createWorker(String suffix,
                                WorkerApprovalStatus approvalStatus,
                                WorkerConnectionStatus connectionStatus,
                                WorkerAvailabilityStatus availabilityStatus) {
        return workerRepository.save(Worker.builder()
                .hostname("m10-worker-" + suffix + "-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(approvalStatus)
                .connectionStatus(connectionStatus)
                .availabilityStatus(availabilityStatus)
                .build());
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

    private static void assertSafeCreateResponse(String response) {
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
}
