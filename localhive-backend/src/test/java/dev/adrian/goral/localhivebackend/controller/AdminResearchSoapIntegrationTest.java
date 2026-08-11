package dev.adrian.goral.localhivebackend.controller;

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
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import dev.adrian.goral.localhivebackend.security.JwtService;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(
        properties = "localhive.artifacts.storage-root=target/test-artifacts/research-soap"
)
@AutoConfigureMockMvc
class AdminResearchSoapIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m28-admin";
    private static final String SOAP_PATH = "/api/admin/research/soap";
    private static final MediaType SOAP_XML = MediaType.parseMediaType("application/soap+xml");
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-08-11T10:00:00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

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
    void shouldEnforceAdminSoapSecurity() throws Exception {
        WorkerCredentials worker = createApprovedWorkerCredentials("security");
        String request = soapEnvelope("GetGroupDetailRequest", executionGroupPayload(UUID.randomUUID()));

        mockMvc.perform(post(SOAP_PATH)
                        .contentType(MediaType.TEXT_XML)
                        .accept(SOAP_XML)
                        .content(request))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(SOAP_PATH)
                        .header(API_KEY_HEADER, worker.rawApiKey())
                        .contentType(MediaType.TEXT_XML)
                        .accept(SOAP_XML)
                        .content(request))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(SOAP_PATH)
                        .with(user("operator").roles("USER"))
                        .contentType(MediaType.TEXT_XML)
                        .accept(SOAP_XML)
                        .content(request))
                .andExpect(status().isForbidden());

        ExecutionGroup group = createGroup("SOAP security group", 1);
        String response = mockMvc.perform(post(SOAP_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(SOAP_XML)
                        .accept(SOAP_XML)
                        .content(soapEnvelope("GetGroupDetailRequest", executionGroupPayload(group.getId()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("<lh:success>true</lh:success>");
        assertSafeSoapResponse(response);
    }

    @Test
    void shouldReturnGroupDetailOverSoapWithoutUnsafeFields() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("SOAP detail group", 1);
        createQueuedShardExecution(group, version, 0, 1, "SOAP detail shard");

        String response = mockMvc.perform(soapRequest("GetGroupDetailRequest", executionGroupPayload(group.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .contains("<lh:GetGroupDetailResponse>")
                .contains("<lh:success>true</lh:success>")
                .contains("<lh:executionGroupId>" + group.getId() + "</lh:executionGroupId>")
                .contains("<lh:displayName>SOAP detail group</lh:displayName>")
                .contains("<lh:status>CREATED</lh:status>")
                .contains("<lh:artifactSummary>");
        assertSafeSoapResponse(response);
    }

    @Test
    void shouldReturnGroupActivityAndArtifactsOverSoap() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("SOAP artifacts group", 1);
        Worker worker = createApprovedWorkerCredentials("artifact").worker();
        WorkExecution shard = createQueuedShardExecution(group, version, 0, 1, "SOAP artifact shard");
        createOutputArtifact(shard, worker, "result.json", "result.json", BASE_TIME.plusSeconds(1));

        String activity = mockMvc.perform(soapRequest("GetGroupActivityRequest", executionGroupPayload(group.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(activity)
                .contains("<lh:GetGroupActivityResponse>")
                .contains("<lh:success>true</lh:success>")
                .contains("<lh:events>")
                .contains("GROUP_CREATED");
        assertSafeSoapResponse(activity);

        String artifacts = mockMvc.perform(soapRequest("GetGroupArtifactsRequest", executionGroupPayload(group.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(artifacts)
                .contains("<lh:GetGroupArtifactsResponse>")
                .contains("<lh:success>true</lh:success>")
                .contains("<lh:totalArtifacts>1</lh:totalArtifacts>")
                .contains("<lh:relativePath>result.json</lh:relativePath>")
                .contains("<lh:preferredOutputs>");
        assertSafeSoapResponse(artifacts);
    }

    @Test
    void shouldReturnSafeSoapErrorsForUnknownGroupUnknownOperationAndMalformedXml() throws Exception {
        String missing = mockMvc.perform(soapRequest("GetGroupDetailRequest", executionGroupPayload(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(missing, "GetGroupDetailResponse", "GROUP_NOT_FOUND");

        String unknown = mockMvc.perform(soapRequest("NotARealOperationRequest", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(unknown, "SoapErrorResponse", "UNKNOWN_OPERATION");

        String malformed = mockMvc.perform(post(SOAP_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.TEXT_XML)
                        .accept(SOAP_XML)
                        .content("<soapenv:Envelope>"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(malformed)
                .contains("<soapenv:Fault>")
                .contains("<lh:reasonCode>MALFORMED_MESSAGE</lh:reasonCode>");
        assertSafeSoapResponse(malformed);
    }

    @Test
    void shouldSupportCancelAndReconcileGroupOverSoap() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup cancellable = createGroup("SOAP cancellable group", 1);
        createQueuedShardExecution(cancellable, version, 0, 1, "SOAP cancellable shard");
        ExecutionGroup terminal = createGroup("SOAP terminal group", 1);
        terminal.markSucceeded(BASE_TIME);
        groupRepository.saveAndFlush(terminal);

        String cancel = mockMvc.perform(soapRequest(
                        "CancelGroupRequest",
                        executionGroupPayload(cancellable.getId()) + "<lh:reason>Stop from SOAP</lh:reason>"
                ))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(cancel)
                .contains("<lh:CancelGroupResponse>")
                .contains("<lh:success>true</lh:success>")
                .contains("<lh:status>CANCELLED</lh:status>");
        assertThat(groupRepository.findById(cancellable.getId()))
                .hasValueSatisfying(group -> assertThat(group.getStatus().name()).isEqualTo("CANCELLED"));
        assertSafeSoapResponse(cancel);

        String conflict = mockMvc.perform(soapRequest("CancelGroupRequest", executionGroupPayload(terminal.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(conflict, "CancelGroupResponse", "OPERATION_CONFLICT");

        String invalidReason = mockMvc.perform(soapRequest(
                        "CancelGroupRequest",
                        executionGroupPayload(createGroup("SOAP reason group", 1).getId())
                                + "<lh:reason>"
                                + "x".repeat(501)
                                + "</lh:reason>"
                ))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(invalidReason, "CancelGroupResponse", "INVALID_PAYLOAD");

        String reconcile = mockMvc.perform(soapRequest("ReconcileGroupRequest", executionGroupPayload(terminal.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(reconcile)
                .contains("<lh:ReconcileGroupResponse>")
                .contains("<lh:success>true</lh:success>")
                .contains("<lh:status>SUCCEEDED</lh:status>");
        assertSafeSoapResponse(reconcile);
    }

    @Test
    void shouldReturnUnsupportedOperationForSoapOperationsOutsideM28() throws Exception {
        String createGroup = mockMvc.perform(soapRequest("CreateExecutionGroupRequest", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(createGroup, "CreateExecutionGroupResponse", "OPERATION_NOT_SUPPORTED");

        String stream = mockMvc.perform(soapRequest("StreamGroupActivityRequest", executionGroupPayload(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(stream, "StreamGroupActivityResponse", "OPERATION_NOT_SUPPORTED");

        String download = mockMvc.perform(soapRequest("DownloadArtifactRequest", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSoapError(download, "DownloadArtifactResponse", "OPERATION_NOT_SUPPORTED");
    }

    @Test
    void shouldKeepReadOnlySoapOperationsReadOnly() throws Exception {
        WorkDefinitionVersion version = noOpVersion();
        ExecutionGroup group = createGroup("SOAP read only group", 1);
        createQueuedShardExecution(group, version, 0, 1, "SOAP read only shard");
        long artifactCount = artifactRepository.count();
        long executionArtifactCount = executionArtifactRepository.count();
        long assignmentCount = assignmentRepository.count();
        long executionCount = executionRepository.count();
        long groupCount = groupRepository.count();

        mockMvc.perform(soapRequest("GetGroupDetailRequest", executionGroupPayload(group.getId())))
                .andExpect(status().isOk());
        mockMvc.perform(soapRequest("GetGroupActivityRequest", executionGroupPayload(group.getId())))
                .andExpect(status().isOk());
        mockMvc.perform(soapRequest("GetGroupArtifactsRequest", executionGroupPayload(group.getId())))
                .andExpect(status().isOk());

        assertThat(artifactRepository.count()).isEqualTo(artifactCount);
        assertThat(executionArtifactRepository.count()).isEqualTo(executionArtifactCount);
        assertThat(assignmentRepository.count()).isEqualTo(assignmentCount);
        assertThat(executionRepository.count()).isEqualTo(executionCount);
        assertThat(groupRepository.count()).isEqualTo(groupCount);
    }

    private MockHttpServletRequestBuilder soapRequest(String operationName, String payload) {
        return post(SOAP_PATH)
                .contentType(MediaType.TEXT_XML)
                .accept(SOAP_XML)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .content(soapEnvelope(operationName, payload));
    }

    private static String soapEnvelope(String operationName, String payload) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:lh="https://localhive.dev/research/soap">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <lh:%s>
                      %s
                    </lh:%s>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(operationName, payload, operationName);
    }

    private static String executionGroupPayload(UUID executionGroupId) {
        return "<lh:executionGroupId>" + executionGroupId + "</lh:executionGroupId>";
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
                "localhive.soap-api-" + UUID.randomUUID(),
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
                .hostname("m28-worker-" + suffix + "-" + UUID.randomUUID())
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

    private static void assertSoapError(String response, String responseName, String reasonCode) {
        assertThat(response)
                .contains("<lh:" + responseName + ">")
                .contains("<lh:success>false</lh:success>")
                .contains("<lh:reasonCode>" + reasonCode + "</lh:reasonCode>")
                .contains("<lh:message>");
        assertSafeSoapResponse(response);
    }

    private static void assertSafeSoapResponse(String response) {
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

    private String adminToken() {
        return jwtService.generateToken(adminUser, adminUser.getId().toString(), "ROLE_ADMIN");
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
