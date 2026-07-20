package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminWorkDefinitionControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String ADMIN_USERNAME = "m9-admin";

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
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    private User adminUser;

    @BeforeEach
    void resetDatabase() {
        versionRepository.deleteAll();
        definitionRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = createUser(ADMIN_USERNAME);
    }

    @Test
    void shouldReturnEmptyDefinitionListWithDefaultPagination() throws Exception {
        mockMvc.perform(get("/api/admin/work-definitions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    void shouldListDefinitionsSortedByLogicalIdWithLatestSummaryAndSafeMetadata() throws Exception {
        WorkDefinitionVersion betaV1 = createLocalDefinition(
                "localhive.beta-task",
                WorkType.TASK,
                "Beta v1",
                "Beta first version",
                "localhive.beta",
                1
        );
        WorkDefinitionVersion betaV2 = addLocalVersion(
                "localhive.beta-task",
                WorkType.TASK,
                "Beta v2",
                "Beta latest version",
                "localhive.beta",
                2
        );
        WorkDefinitionVersion alpha = createLocalDefinition(
                "adrian.alpha-workload",
                WorkType.WORKLOAD,
                "Alpha workload",
                "Alpha description",
                "adrian.alpha-worker",
                1
        );

        String response = mockMvc.perform(get("/api/admin/work-definitions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].definitionId").value(alpha.getDefinition().getId().toString()))
                .andExpect(jsonPath("$.items[0].logicalId").value("adrian.alpha-workload"))
                .andExpect(jsonPath("$.items[0].type").value("WORKLOAD"))
                .andExpect(jsonPath("$.items[0].sourceType").value("LOCAL"))
                .andExpect(jsonPath("$.items[0].name").value("Alpha workload"))
                .andExpect(jsonPath("$.items[0].description").value("Alpha description"))
                .andExpect(jsonPath("$.items[0].latestVersion").value(1))
                .andExpect(jsonPath("$.items[0].versionCount").value(1))
                .andExpect(jsonPath("$.items[0].latestVersionId").value(alpha.getId().toString()))
                .andExpect(jsonPath("$.items[0].latestExecutorId").value("adrian.alpha-worker"))
                .andExpect(jsonPath("$.items[0].latestExecutorContractVersion").value(1))
                .andExpect(jsonPath("$.items[0].latestApprovalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[1].definitionId").value(betaV1.getDefinition().getId().toString()))
                .andExpect(jsonPath("$.items[1].logicalId").value("localhive.beta-task"))
                .andExpect(jsonPath("$.items[1].type").value("TASK"))
                .andExpect(jsonPath("$.items[1].name").value("Beta v2"))
                .andExpect(jsonPath("$.items[1].description").value("Beta latest version"))
                .andExpect(jsonPath("$.items[1].latestVersion").value(2))
                .andExpect(jsonPath("$.items[1].versionCount").value(2))
                .andExpect(jsonPath("$.items[1].latestVersionId").value(betaV2.getId().toString()))
                .andExpect(jsonPath("$.items[1].latestExecutorContractVersion").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminWorkDefinitionResponse(response);
    }

    @Test
    void shouldFilterPaginateAndRejectInvalidListParameters() throws Exception {
        WorkDefinitionVersion alpha = createLocalDefinition(
                "adrian.alpha-task",
                WorkType.TASK,
                "Alpha task",
                null,
                "adrian.alpha",
                1
        );
        createLocalDefinition(
                "adrian.beta-task",
                WorkType.TASK,
                "Beta task",
                null,
                "adrian.beta",
                1
        );
        WorkDefinitionVersion delta = createLocalDefinition(
                "adrian.delta-workload",
                WorkType.WORKLOAD,
                "Delta workload",
                null,
                "adrian.delta",
                1
        );
        WorkDefinitionVersion gamma = createLocalDefinition(
                "localhive.gamma-task",
                WorkType.TASK,
                "Gamma task",
                null,
                "localhive.gamma",
                1
        );

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("limit", "2")
                        .param("offset", "2")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(2))
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.items[0].definitionId").value(delta.getDefinition().getId().toString()))
                .andExpect(jsonPath("$.items[1].definitionId").value(gamma.getDefinition().getId().toString()));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("limit", "200")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(200))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalCount").value(4));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("type", "WORKLOAD")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].definitionId").value(delta.getDefinition().getId().toString()))
                .andExpect(jsonPath("$.items[0].type").value("WORKLOAD"))
                .andExpect(jsonPath("$.totalCount").value(1));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("logicalId", "adrian.alpha-task")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].definitionId").value(alpha.getDefinition().getId().toString()))
                .andExpect(jsonPath("$.items[0].logicalId").value("adrian.alpha-task"))
                .andExpect(jsonPath("$.totalCount").value(1));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("logicalId", "localhive.unknown")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCount").value(0));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("limit", "0")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("limit", "201")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("limit", "not-a-number")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be a whole number."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("offset", "-1")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("offset must be greater than or equal to 0."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .param("type", "NOT_A_TYPE")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown work definition type: NOT_A_TYPE"));
    }

    @Test
    void shouldReturnDefinitionDetailWithVersionsNewestFirstAndSafeMetadata() throws Exception {
        WorkDefinitionVersion firstVersion = createLocalDefinition(
                "localhive.detail-task",
                WorkType.TASK,
                "Detail v1",
                "First detail version",
                "localhive.detail",
                1
        );
        WorkDefinitionVersion latestVersion = addLocalVersion(
                "localhive.detail-task",
                WorkType.TASK,
                "Detail v2",
                "Latest detail version",
                "localhive.detail",
                2
        );

        String response = mockMvc.perform(get(
                        "/api/admin/work-definitions/{definitionId}",
                        firstVersion.getDefinition().getId()
                )
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionId").value(firstVersion.getDefinition().getId().toString()))
                .andExpect(jsonPath("$.logicalId").value("localhive.detail-task"))
                .andExpect(jsonPath("$.type").value("TASK"))
                .andExpect(jsonPath("$.sourceType").value("LOCAL"))
                .andExpect(jsonPath("$.name").value("Detail v2"))
                .andExpect(jsonPath("$.description").value("Latest detail version"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[0].versionId").value(latestVersion.getId().toString()))
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[0].latest").value(true))
                .andExpect(jsonPath("$.versions[0].name").value("Detail v2"))
                .andExpect(jsonPath("$.versions[0].description").value("Latest detail version"))
                .andExpect(jsonPath("$.versions[0].executorId").value("localhive.detail"))
                .andExpect(jsonPath("$.versions[0].executorContractVersion").value(2))
                .andExpect(jsonPath("$.versions[0].approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.versions[0].createdAt").exists())
                .andExpect(jsonPath("$.versions[1].versionId").value(firstVersion.getId().toString()))
                .andExpect(jsonPath("$.versions[1].version").value(1))
                .andExpect(jsonPath("$.versions[1].latest").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSafeAdminWorkDefinitionResponse(response);
    }

    @Test
    void shouldReturnNotFoundAndBadRequestForMissingOrInvalidDefinitionDetail() throws Exception {
        mockMvc.perform(get("/api/admin/work-definitions/{definitionId}", UUID.randomUUID())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Work definition not found."));

        mockMvc.perform(get("/api/admin/work-definitions/{definitionId}", "not-a-uuid")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldEnforceAdminSecurityForWorkDefinitionEndpoints() throws Exception {
        WorkDefinitionVersion version = createLocalDefinition(
                "localhive.secure-task",
                WorkType.TASK,
                "Secure task",
                null,
                "localhive.secure",
                1
        );
        WorkerCredentials credentials = createApprovedWorkerCredentials("work-definition-security");

        mockMvc.perform(get("/api/admin/work-definitions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/work-definitions/{definitionId}", version.getDefinition().getId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/work-definitions/{definitionId}", version.getDefinition().getId())
                        .header(API_KEY_HEADER, credentials.rawApiKey())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication failed."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/work-definitions/{definitionId}", version.getDefinition().getId())
                        .with(user("operator").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied."));

        mockMvc.perform(get("/api/admin/work-definitions")
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/work-definitions/{definitionId}", version.getDefinition().getId())
                        .with(admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private WorkDefinitionVersion createLocalDefinition(String logicalId,
                                                        WorkType workType,
                                                        String name,
                                                        String description,
                                                        String executorId,
                                                        int executorContractVersion) {
        return definitionManagementService.createLocalDefinition(command(
                logicalId,
                workType,
                name,
                description,
                executorId,
                executorContractVersion
        ));
    }

    private WorkDefinitionVersion addLocalVersion(String logicalId,
                                                  WorkType workType,
                                                  String name,
                                                  String description,
                                                  String executorId,
                                                  int executorContractVersion) {
        return definitionManagementService.addLocalVersion(command(
                logicalId,
                workType,
                name,
                description,
                executorId,
                executorContractVersion
        ));
    }

    private DefinitionContentCommand command(String logicalId,
                                             WorkType workType,
                                             String name,
                                             String description,
                                             String executorId,
                                             int executorContractVersion) {
        return new DefinitionContentCommand(
                logicalId,
                workType,
                name,
                description,
                executorId,
                executorContractVersion,
                unsafeConfiguration(),
                ResourceRequest.of(128, 1, false),
                adminUser.getId()
        );
    }

    private WorkerCredentials createApprovedWorkerCredentials(String suffix) {
        String rawApiKey = "worker-api-key-" + UUID.randomUUID();
        Worker worker = workerRepository.save(Worker.builder()
                .hostname("worker-" + suffix + "-" + UUID.randomUUID())
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

    private static ObjectNode unsafeConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("message", "noop");
        configuration.put("secretConfig", "do-not-expose-config");
        configuration.put("apiKey", "do-not-expose-api-key");
        configuration.put("storagePath", "C:\\secret\\artifact.txt");
        return configuration;
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("ADMIN");
    }

    private static void assertSafeAdminWorkDefinitionResponse(String response) {
        assertThat(response)
                .doesNotContain("executorConfiguration")
                .doesNotContain("secretConfig")
                .doesNotContain("do-not-expose-config")
                .doesNotContain("do-not-expose-api-key")
                .doesNotContain("contentChecksum")
                .doesNotContain("apiKey")
                .doesNotContain("apiKeyHash")
                .doesNotContain("passwordHash")
                .doesNotContain("leaseToken")
                .doesNotContain("leaseTokenHash")
                .doesNotContain("leaseExpiresAt")
                .doesNotContain("storagePath")
                .doesNotContain("dataRoot")
                .doesNotContain("C:\\secret")
                .doesNotContain("Exception")
                .doesNotContain("StackTrace");
    }

    private record WorkerCredentials(Worker worker, String rawApiKey) {
    }
}
