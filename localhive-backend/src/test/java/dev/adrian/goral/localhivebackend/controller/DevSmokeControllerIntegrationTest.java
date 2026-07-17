package dev.adrian.goral.localhivebackend.controller;

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
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
