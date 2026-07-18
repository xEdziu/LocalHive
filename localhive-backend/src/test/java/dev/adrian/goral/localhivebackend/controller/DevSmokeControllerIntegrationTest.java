package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevSmokeControllerIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final String DOCKER_WORKLOAD_PATH = "/api/dev/smoke/workers/{workerId}/docker-workload";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private WorkDefinitionRepository definitionRepository;

    @Autowired
    private WorkDefinitionVersionRepository versionRepository;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private ExecutionAssignmentRepository assignmentRepository;

    @Test
    void shouldCreateNoOpExecutionAndAssignItToWorker() throws Exception {
        createUser("dev-smoke-admin");
        Worker worker = createApprovedOnlineAvailableWorker();

        String response = mockMvc.perform(post("/api/dev/smoke/workers/{workerId}/no-op", worker.getId())
                        .with(user("dev-smoke-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.executorId").value("localhive.no-op"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionId = UUID.fromString(JsonPath.read(response, "$.executionId"));

        assertThat(definitionRepository.findByLogicalIdentifier("localhive.no-op"))
                .hasValueSatisfying(definition ->
                        assertThat(versionRepository.findByDefinitionAndVersionNumber(definition, 1))
                                .hasValueSatisfying(version -> {
                                    assertThat(version.getExecutorId()).isEqualTo("localhive.no-op");
                                    assertThat(version.getExecutorContractVersion()).isEqualTo(1);
                                }));
        assertThat(executionRepository.findById(executionId))
                .hasValueSatisfying(execution -> {
                    assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED);
                    assertThat(assignmentRepository.findByExecution(execution))
                            .hasValueSatisfying(assignment -> {
                                assertThat(assignment.getExecution().getId()).isEqualTo(executionId);
                                assertThat(assignment.getWorker().getId()).isEqualTo(worker.getId());
                                assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.REQUIRE);
                            });
                });
    }

    @Test
    void shouldCreateDockerWorkloadExecutionWithDefaultRequestAndAssignItToWorker() throws Exception {
        createUser("dev-smoke-docker-admin");
        Worker worker = createApprovedOnlineAvailableWorker();

        String response = mockMvc.perform(post(DOCKER_WORKLOAD_PATH, worker.getId())
                        .with(user("dev-smoke-docker-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.executorId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.image").value("alpine:3.20"))
                .andExpect(jsonPath("$.timeoutSeconds").value(30))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionId = UUID.fromString(JsonPath.read(response, "$.executionId"));

        assertDockerDefinition();
        assertThat(executionRepository.findById(executionId))
                .hasValueSatisfying(execution -> {
                    assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED);
                    assertThat(execution.getInstance()).isNull();
                    assertDockerConfiguration(
                            execution.getResolvedConfigurationSnapshot(),
                            "echo LocalHive Docker workload",
                            30,
                            128,
                            1
                    );
                    assertThat(execution.getResolvedResourceRequest().getRequiredRamMb()).isEqualTo(128);
                    assertThat(execution.getResolvedResourceRequest().getRequiredCpuCores()).isEqualTo(1);
                    assertThat(execution.getResolvedResourceRequest().isGpuRequired()).isFalse();
                    assertThat(assignmentRepository.findByExecution(execution))
                            .hasValueSatisfying(assignment -> {
                                assertThat(assignment.getExecution().getId()).isEqualTo(executionId);
                                assertThat(assignment.getWorker().getId()).isEqualTo(worker.getId());
                                assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.REQUIRE);
                            });
                });
    }

    @Test
    void shouldCreateDockerWorkloadExecutionWithCustomAllowedRequest() throws Exception {
        createUser("dev-smoke-custom-docker-admin");
        Worker worker = createApprovedOnlineAvailableWorker();

        String response = mockMvc.perform(post(DOCKER_WORKLOAD_PATH, worker.getId())
                        .with(user("dev-smoke-custom-docker-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": ["sh", "-c", "echo custom"],
                                  "timeoutSeconds": 45,
                                  "resources": {
                                    "memoryMb": 256,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(worker.getId().toString()))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.executorId").value("localhive.docker.workload"))
                .andExpect(jsonPath("$.executorContractVersion").value(1))
                .andExpect(jsonPath("$.image").value("alpine:3.20"))
                .andExpect(jsonPath("$.timeoutSeconds").value(45))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID executionId = UUID.fromString(JsonPath.read(response, "$.executionId"));

        assertThat(executionRepository.findById(executionId))
                .hasValueSatisfying(execution -> {
                    assertThat(execution.getStatus()).isEqualTo(WorkExecutionStatus.ASSIGNED);
                    assertDockerConfiguration(
                            execution.getResolvedConfigurationSnapshot(),
                            "echo custom",
                            45,
                            256,
                            1
                    );
                    assertThat(execution.getResolvedResourceRequest().getRequiredRamMb()).isEqualTo(256);
                    assertThat(execution.getResolvedResourceRequest().getRequiredCpuCores()).isEqualTo(1);
                    assertThat(execution.getResolvedResourceRequest().isGpuRequired()).isFalse();
                    assertThat(assignmentRepository.findByExecution(execution))
                            .hasValueSatisfying(assignment ->
                                    assertThat(assignment.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.REQUIRE));
                });
    }

    @Test
    void shouldRejectNonAllowlistedDockerImage() throws Exception {
        createUser("dev-smoke-invalid-image-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-invalid-image-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "ubuntu:24.04",
                                  "command": ["sh", "-c", "echo LocalHive Docker workload"],
                                  "timeoutSeconds": 30,
                                  "resources": {
                                    "memoryMb": 128,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectGpuRequiredDockerWorkload() throws Exception {
        createUser("dev-smoke-invalid-gpu-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-invalid-gpu-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": ["sh", "-c", "echo LocalHive Docker workload"],
                                  "timeoutSeconds": 30,
                                  "resources": {
                                    "memoryMb": 128,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": true
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidDockerCommand() throws Exception {
        createUser("dev-smoke-invalid-command-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-invalid-command-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": [],
                                  "timeoutSeconds": 30,
                                  "resources": {
                                    "memoryMb": 128,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidDockerTimeout() throws Exception {
        createUser("dev-smoke-invalid-timeout-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-invalid-timeout-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": ["sh", "-c", "echo LocalHive Docker workload"],
                                  "timeoutSeconds": 301,
                                  "resources": {
                                    "memoryMb": 128,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidDockerMemory() throws Exception {
        createUser("dev-smoke-invalid-memory-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-invalid-memory-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": ["sh", "-c", "echo LocalHive Docker workload"],
                                  "timeoutSeconds": 30,
                                  "resources": {
                                    "memoryMb": 15,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidDockerCpuCores() throws Exception {
        createUser("dev-smoke-invalid-cpu-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-invalid-cpu-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": ["sh", "-c", "echo LocalHive Docker workload"],
                                  "timeoutSeconds": 30,
                                  "resources": {
                                    "memoryMb": 128,
                                    "cpuCores": 9
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectBlankDockerCommandElement() throws Exception {
        createUser("dev-smoke-blank-command-admin");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-blank-command-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "image": "alpine:3.20",
                                  "command": ["sh", "   "],
                                  "timeoutSeconds": 30,
                                  "resources": {
                                    "memoryMb": 128,
                                    "cpuCores": 1
                                  },
                                  "gpu": {
                                    "required": false
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireAdminForDockerWorkloadSmokeEndpoint() throws Exception {
        createUser("dev-smoke-regular-user");

        mockMvc.perform(post(DOCKER_WORKLOAD_PATH, UUID.randomUUID())
                        .with(user("dev-smoke-regular-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    private void assertDockerDefinition() {
        assertThat(definitionRepository.findByLogicalIdentifier("localhive.docker.workload"))
                .hasValueSatisfying(definition ->
                        assertThat(versionRepository.findByDefinitionAndVersionNumber(definition, 1))
                                .hasValueSatisfying(version -> {
                                    assertThat(version.getExecutorId()).isEqualTo("localhive.docker.workload");
                                    assertThat(version.getExecutorContractVersion()).isEqualTo(1);
                                    assertThat(version.getDefaultResourceRequest().getRequiredRamMb()).isEqualTo(128);
                                    assertThat(version.getDefaultResourceRequest().getRequiredCpuCores()).isEqualTo(1);
                                    assertThat(version.getDefaultResourceRequest().isGpuRequired()).isFalse();
                                }));
    }

    private static void assertDockerConfiguration(JsonNode configuration,
                                                  String commandText,
                                                  int timeoutSeconds,
                                                  int memoryMb,
                                                  int cpuCores) {
        assertThat(configuration.get("image").asText()).isEqualTo("alpine:3.20");
        assertThat(configuration.get("command").get(0).asText()).isEqualTo("sh");
        assertThat(configuration.get("command").get(1).asText()).isEqualTo("-c");
        assertThat(configuration.get("command").get(2).asText()).isEqualTo(commandText);
        assertThat(configuration.get("timeoutSeconds").asInt()).isEqualTo(timeoutSeconds);
        assertThat(configuration.get("resources").get("memoryMb").asInt()).isEqualTo(memoryMb);
        assertThat(configuration.get("resources").get("cpuCores").asInt()).isEqualTo(cpuCores);
        assertThat(configuration.get("gpu").get("required").asBoolean()).isFalse();
    }

    private Worker createApprovedOnlineAvailableWorker() {
        return workerRepository.save(Worker.builder()
                .hostname("dev-smoke-worker-" + UUID.randomUUID())
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .build());
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID())
                .passwordHash("hashed-password")
                .build());
    }
}
