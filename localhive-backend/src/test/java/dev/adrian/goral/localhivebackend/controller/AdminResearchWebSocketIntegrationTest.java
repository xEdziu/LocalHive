package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupFailurePolicy;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupMergeMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
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
import dev.adrian.goral.localhivebackend.security.JwtService;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "localhive.artifacts.storage-root=target/test-artifacts/research-websocket"
)
@AutoConfigureMockMvc
class AdminResearchWebSocketIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m27-admin";
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-27T10:00:00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private HandshakeInterceptor adminOnlyHandshakeInterceptor;

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

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
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = userRepository.save(User.builder()
                .username(ADMIN_USERNAME + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }

    @Test
    void shouldEnforceAdminWebSocketHandshakeSecurity() throws Exception {
        WorkerCredentials worker = createApprovedWorkerCredentials("security");

        assertThatThrownBy(() -> connect(new WebSocketHttpHeaders()))
                .isInstanceOf(Exception.class);

        WebSocketHttpHeaders workerHeaders = new WebSocketHttpHeaders();
        workerHeaders.add(API_KEY_HEADER, worker.rawApiKey());
        assertThatThrownBy(() -> connect(workerHeaders))
                .isInstanceOf(Exception.class);

        var userAuthentication = new UsernamePasswordAuthenticationToken(
                "operator",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getPrincipal()).thenReturn(userAuthentication);

        assertThat(adminOnlyHandshakeInterceptor.beforeHandshake(
                request,
                response,
                mock(WebSocketHandler.class),
                new HashMap<>()
        )).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);

        TestWebSocket admin = connect(adminHeaders());
        admin.close();
    }

    @Test
    void shouldReturnGroupDetailOverWebSocketWithoutUnsafeFields() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("WS detail group", 1);
        createQueuedShardExecution(group, version, 0, 1, "WS detail shard");

        TestWebSocket websocket = connectAdmin();
        websocket.send(groupRequest("detail-1", "GET_GROUP_DETAIL", group.getId()));

        JsonNode response = websocket.readJson();

        assertThat(response.path("type").asText()).isEqualTo("RESPONSE");
        assertThat(response.path("requestId").asText()).isEqualTo("detail-1");
        assertThat(response.path("operation").asText()).isEqualTo("GET_GROUP_DETAIL");
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.path("data").path("executionGroupId").asText()).isEqualTo(group.getId().toString());
        assertThat(response.path("data").path("displayName").asText()).isEqualTo("WS detail group");
        assertSafeWebSocketResponse(response.toString());
        websocket.close();
    }

    @Test
    void shouldReturnGroupActivityAndArtifactsOverWebSocket() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("WS artifact group", 1);
        Worker worker = createApprovedWorkerCredentials("artifact").worker();
        WorkExecution shard = createQueuedShardExecution(group, version, 0, 1, "WS artifact shard");
        createOutputArtifact(shard, worker, "result.json", "result.json", BASE_TIME.plusSeconds(1));

        TestWebSocket websocket = connectAdmin();
        websocket.send(groupRequest("activity-1", "GET_GROUP_ACTIVITY", group.getId()));
        JsonNode activity = websocket.readJson();

        assertThat(activity.path("type").asText()).isEqualTo("RESPONSE");
        assertThat(activity.path("operation").asText()).isEqualTo("GET_GROUP_ACTIVITY");
        assertThat(activity.path("success").asBoolean()).isTrue();
        assertThat(activity.path("data").path("executionGroupId").asText()).isEqualTo(group.getId().toString());
        assertThat(activity.path("data").path("events").isArray()).isTrue();
        assertSafeWebSocketResponse(activity.toString());

        websocket.send(groupRequest("artifacts-1", "GET_GROUP_ARTIFACTS", group.getId()));
        JsonNode artifacts = websocket.readJson();

        assertThat(artifacts.path("type").asText()).isEqualTo("RESPONSE");
        assertThat(artifacts.path("operation").asText()).isEqualTo("GET_GROUP_ARTIFACTS");
        assertThat(artifacts.path("success").asBoolean()).isTrue();
        assertThat(artifacts.path("data").path("artifactSummary").path("totalArtifacts").asInt()).isEqualTo(1);
        assertThat(artifacts.path("data").path("preferredOutputs").get(0).path("relativePath").asText())
                .isEqualTo("result.json");
        assertSafeWebSocketResponse(artifacts.toString());
        websocket.close();
    }

    @Test
    void shouldReturnSafeErrorsForUnknownGroupUnknownOperationAndMalformedJson() throws Exception {
        TestWebSocket websocket = connectAdmin();

        websocket.send(groupRequest("missing-1", "GET_GROUP_DETAIL", UUID.randomUUID()));
        JsonNode missing = websocket.readJson();
        assertError(missing, "missing-1", "GET_GROUP_DETAIL", "GROUP_NOT_FOUND");

        websocket.send("""
                {
                  "requestId": "unknown-1",
                  "operation": "NOT_A_REAL_OPERATION",
                  "payload": {}
                }
                """);
        JsonNode unknown = websocket.readJson();
        assertError(unknown, "unknown-1", "NOT_A_REAL_OPERATION", "UNKNOWN_OPERATION");

        websocket.send("{");
        JsonNode malformed = websocket.readJson();
        assertError(malformed, null, null, "MALFORMED_MESSAGE");
        assertSafeWebSocketResponse(malformed.toString());
        websocket.close();
    }

    @Test
    void shouldValidateStreamParametersOverWebSocket() throws Exception {
        ExecutionGroup group = createGroup("WS invalid stream params", 1);
        TestWebSocket websocket = connectAdmin();

        websocket.send(streamRequest("bad-poll", group.getId(), 499, 1000, false, 2));
        assertError(websocket.readJson(), "bad-poll", "STREAM_GROUP_ACTIVITY", "INVALID_PAYLOAD");

        websocket.send(streamRequest("bad-heartbeat", group.getId(), 2000, 1000, false, 2));
        assertError(websocket.readJson(), "bad-heartbeat", "STREAM_GROUP_ACTIVITY", "INVALID_PAYLOAD");

        websocket.send(streamRequest("bad-max", group.getId(), 500, 1000, false, 0));
        assertError(websocket.readJson(), "bad-max", "STREAM_GROUP_ACTIVITY", "INVALID_PAYLOAD");
        websocket.close();
    }

    @Test
    void shouldStreamGroupActivityAndStopStreamOverWebSocket() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("WS stream group", 1);
        createQueuedShardExecution(group, version, 0, 1, "WS stream shard");

        TestWebSocket websocket = connectAdmin();
        websocket.send(streamRequest("stream-1", group.getId(), 10000, 10000, false, 100));

        JsonNode groupDetailEvent = websocket.readJson();
        JsonNode activityEvent = websocket.readJson();
        assertEvent(groupDetailEvent, "stream-1", "group-detail");
        assertEvent(activityEvent, "stream-1", "activity-snapshot");
        assertThat(groupDetailEvent.path("data").path("executionGroupId").asText()).isEqualTo(group.getId().toString());
        assertThat(activityEvent.path("data").path("events").isArray()).isTrue();
        assertSafeWebSocketResponse(groupDetailEvent.toString());
        assertSafeWebSocketResponse(activityEvent.toString());

        websocket.send("""
                {
                  "requestId": "stop-1",
                  "operation": "STOP_STREAM_GROUP_ACTIVITY",
                  "payload": {
                    "streamRequestId": "stream-1"
                  }
                }
                """);
        JsonNode stopResponse = websocket.readJson();

        assertThat(stopResponse.path("type").asText()).isEqualTo("RESPONSE");
        assertThat(stopResponse.path("operation").asText()).isEqualTo("STOP_STREAM_GROUP_ACTIVITY");
        assertThat(stopResponse.path("success").asBoolean()).isTrue();
        assertThat(stopResponse.path("data").path("streamRequestId").asText()).isEqualTo("stream-1");
        assertThat(stopResponse.path("data").path("stopped").asBoolean()).isTrue();
        websocket.close();
    }

    @Test
    void shouldStreamHeartbeatWhenSnapshotsDoNotChange() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("WS heartbeat group", 1);
        createQueuedShardExecution(group, version, 0, 1, "WS heartbeat shard");

        TestWebSocket websocket = connectAdmin();
        websocket.send(streamRequest("stream-heartbeat", group.getId(), 500, 1000, false, 3));

        assertEvent(websocket.readJson(), "stream-heartbeat", "group-detail");
        assertEvent(websocket.readJson(), "stream-heartbeat", "activity-snapshot");
        JsonNode heartbeat = websocket.readJson();

        assertEvent(heartbeat, "stream-heartbeat", "heartbeat");
        assertThat(heartbeat.path("data").path("executionGroupId").asText()).isEqualTo(group.getId().toString());
        assertThat(heartbeat.path("data").path("generatedAt").asText()).isNotBlank();
        assertThat(heartbeat.path("data").path("status").asText()).isEqualTo("CREATED");
        assertSafeWebSocketResponse(heartbeat.toString());
        websocket.close();
    }

    @Test
    void shouldSupportCancelAndReconcileGroupOverWebSocket() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup cancellable = createGroup("WS cancellable group", 1);
        createQueuedShardExecution(cancellable, version, 0, 1, "WS cancellable shard");
        ExecutionGroup terminal = createGroup("WS terminal group", 1);
        terminal.markSucceeded(BASE_TIME);
        groupRepository.saveAndFlush(terminal);

        TestWebSocket websocket = connectAdmin();
        websocket.send("""
                {
                  "requestId": "cancel-1",
                  "operation": "CANCEL_GROUP",
                  "payload": {
                    "executionGroupId": "%s",
                    "reason": "Stop from WebSocket"
                  }
                }
                """.formatted(cancellable.getId()));
        JsonNode cancelResponse = websocket.readJson();

        assertThat(cancelResponse.path("type").asText()).isEqualTo("RESPONSE");
        assertThat(cancelResponse.path("success").asBoolean()).isTrue();
        assertThat(cancelResponse.path("data").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(groupRepository.findById(cancellable.getId()))
                .hasValueSatisfying(group -> assertThat(group.getStatus().name()).isEqualTo("CANCELLED"));
        assertSafeWebSocketResponse(cancelResponse.toString());

        websocket.send(groupRequest("cancel-conflict", "CANCEL_GROUP", terminal.getId()));
        JsonNode conflict = websocket.readJson();
        assertError(conflict, "cancel-conflict", "CANCEL_GROUP", "OPERATION_CONFLICT");

        websocket.send(groupRequest("reconcile-1", "RECONCILE_GROUP", terminal.getId()));
        JsonNode reconcileResponse = websocket.readJson();

        assertThat(reconcileResponse.path("type").asText()).isEqualTo("RESPONSE");
        assertThat(reconcileResponse.path("success").asBoolean()).isTrue();
        assertThat(reconcileResponse.path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        assertSafeWebSocketResponse(reconcileResponse.toString());
        websocket.close();
    }

    @Test
    void shouldReflectWebSocketAvailabilityInProtocolContract() throws Exception {
        mockMvc.perform(get("/api/admin/research/protocol-contract")
                        .with(user(ADMIN_USERNAME).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocols[?(@.protocol == 'WEBSOCKET')].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.protocols[?(@.protocol == 'SOAP')].status").value("PLANNED"));

        mockMvc.perform(post("/api/admin/research/protocol-contract/validate")
                        .with(user(ADMIN_USERNAME).roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "protocol": "WEBSOCKET",
                                  "operation": "GET_GROUP_DETAIL",
                                  "dataTransferMode": "INLINE_JSON",
                                  "payloadFormat": "JSON"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/admin/research/protocol-contract/validate")
                        .with(user(ADMIN_USERNAME).roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "protocol": "WEBSOCKET",
                                  "operation": "STREAM_GROUP_ACTIVITY",
                                  "dataTransferMode": "STREAMED_EVENTS",
                                  "payloadFormat": "JSON"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/admin/research/protocol-contract/validate")
                        .with(user(ADMIN_USERNAME).roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "protocol": "WEBSOCKET",
                                  "operation": "DOWNLOAD_ARTIFACT",
                                  "dataTransferMode": "OUTPUT_ARTIFACT",
                                  "payloadFormat": "BINARY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void shouldKeepReadOnlyWebSocketOperationsReadOnly() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("WS read only group", 1);
        createQueuedShardExecution(group, version, 0, 1, "WS read only shard");
        long artifactCount = artifactRepository.count();
        long executionArtifactCount = executionArtifactRepository.count();
        long assignmentCount = assignmentRepository.count();
        long executionCount = executionRepository.count();
        long groupCount = groupRepository.count();

        TestWebSocket websocket = connectAdmin();
        websocket.send(groupRequest("detail-ro", "GET_GROUP_DETAIL", group.getId()));
        assertThat(websocket.readJson().path("success").asBoolean()).isTrue();
        websocket.send(groupRequest("activity-ro", "GET_GROUP_ACTIVITY", group.getId()));
        assertThat(websocket.readJson().path("success").asBoolean()).isTrue();
        websocket.send(groupRequest("artifacts-ro", "GET_GROUP_ARTIFACTS", group.getId()));
        assertThat(websocket.readJson().path("success").asBoolean()).isTrue();
        websocket.send(streamRequest("stream-ro", group.getId(), 500, 1000, false, 2));
        assertEvent(websocket.readJson(), "stream-ro", "group-detail");
        assertEvent(websocket.readJson(), "stream-ro", "activity-snapshot");
        websocket.close();

        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(executionArtifactRepository.count()).isEqualTo(executionArtifactCount);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);
    }

    private TestWebSocket connectAdmin() throws Exception {
        return connect(adminHeaders());
    }

    private TestWebSocket connect(WebSocketHttpHeaders headers) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler handler = new TestWebSocketHandler();
        WebSocketSession session = client.execute(
                handler,
                headers,
                URI.create("ws://localhost:" + port + "/api/admin/research/ws")
        ).get(5, TimeUnit.SECONDS);
        return new TestWebSocket(session, handler.messages);
    }

    private WebSocketHttpHeaders adminHeaders() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken());
        return headers;
    }

    private String adminToken() {
        return jwtService.generateToken(adminUser, adminUser.getId().toString(), "ROLE_ADMIN");
    }

    private static String groupRequest(String requestId, String operation, UUID executionGroupId) {
        return """
                {
                  "requestId": "%s",
                  "operation": "%s",
                  "payload": {
                    "executionGroupId": "%s"
                  }
                }
                """.formatted(requestId, operation, executionGroupId);
    }

    private static String streamRequest(String requestId,
                                        UUID executionGroupId,
                                        int pollIntervalMs,
                                        int heartbeatIntervalMs,
                                        boolean closeOnTerminal,
                                        int maxEvents) {
        return """
                {
                  "requestId": "%s",
                  "operation": "STREAM_GROUP_ACTIVITY",
                  "payload": {
                    "executionGroupId": "%s",
                    "pollIntervalMs": %d,
                    "heartbeatIntervalMs": %d,
                    "closeOnTerminal": %s,
                    "maxEvents": %d
                  }
                }
                """.formatted(requestId, executionGroupId, pollIntervalMs, heartbeatIntervalMs, closeOnTerminal, maxEvents);
    }

    private ExecutionGroup createGroup(String displayName, int shardCount) {
        return groupRepository.saveAndFlush(ExecutionGroup.create(
                displayName,
                ExecutionGroupMergeMode.NONE,
                ExecutionGroupFailurePolicy.FAIL_FAST,
                shardCount,
                LocalDateTime.now()
        ));
    }

    private WorkExecution createQueuedShardExecution(ExecutionGroup group,
                                                     WorkDefinitionVersion version,
                                                     int shardIndex,
                                                     int shardCount,
                                                     String displayName) {
        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                JsonNodeFactory.instance.objectNode(),
                null,
                displayName
        ));
        execution.attachToGroupAsShard(group, shardIndex, shardCount);
        return executionRepository.saveAndFlush(execution);
    }

    private WorkDefinitionVersion noOpVersion() {
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive.websocket-api-" + UUID.randomUUID(),
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

    private WorkerCredentials createApprovedWorkerCredentials(String suffix) {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("m27-worker-" + suffix + "-" + UUID.randomUUID())
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

    private void assertError(JsonNode response, String requestId, String operation, String reasonCode) {
        assertThat(response.path("type").asText()).isEqualTo("ERROR");
        assertThat(response.path("success").asBoolean()).isFalse();
        if (requestId == null) {
            assertThat(response.path("requestId").isNull()).isTrue();
        } else {
            assertThat(response.path("requestId").asText()).isEqualTo(requestId);
        }
        if (operation == null) {
            assertThat(response.path("operation").isNull()).isTrue();
        } else {
            assertThat(response.path("operation").asText()).isEqualTo(operation);
        }
        assertThat(response.path("error").path("reasonCode").asText()).isEqualTo(reasonCode);
        assertThat(response.path("error").path("message").asText()).isNotBlank();
        assertSafeWebSocketResponse(response.toString());
    }

    private static void assertEvent(JsonNode response, String requestId, String event) {
        assertThat(response.path("type").asText()).isEqualTo("EVENT");
        assertThat(response.path("requestId").asText()).isEqualTo(requestId);
        assertThat(response.path("operation").asText()).isEqualTo("STREAM_GROUP_ACTIVITY");
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.path("event").asText()).isEqualTo(event);
    }

    private static void assertSafeWebSocketResponse(String response) {
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
                .doesNotContain("mergeConfigurationTemplate")
                .doesNotContain("mergePlan")
                .doesNotContain("storagePath")
                .doesNotContain("storageRoot")
                .doesNotContain("dataRoot")
                .doesNotContain("target/test-artifacts")
                .doesNotContain("Exception")
                .doesNotContain("StackTrace")
                .doesNotContain("$OUTPUT_DIR");
    }

    private final class TestWebSocket {
        private final WebSocketSession session;
        private final BlockingQueue<String> messages;

        private TestWebSocket(WebSocketSession session, BlockingQueue<String> messages) {
            this.session = session;
            this.messages = messages;
        }

        private void send(String message) throws Exception {
            session.sendMessage(new TextMessage(message));
        }

        private JsonNode readJson() throws Exception {
            String rawMessage = messages.poll(5, TimeUnit.SECONDS);
            assertThat(rawMessage).isNotNull();
            return objectMapper.readTree(rawMessage);
        }

        private void close() throws Exception {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    private static final class TestWebSocketHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
