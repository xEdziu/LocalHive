package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAttempt;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAttemptRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkExecutionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class WorkDomainIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.2-alpine");
    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-17T10:00:00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private DefinitionManagementService definitionManagementService;

    @Autowired
    private WorkInstanceManagementService instanceManagementService;

    @Autowired
    private WorkExecutionCreationService creationService;

    @Autowired
    private WorkExecutionAssignmentService assignmentService;

    @Autowired
    private WorkExecutionLifecycleService lifecycleService;

    @Autowired
    private WorkExecutionRepository executionRepository;

    @Autowired
    private WorkInstanceRepository instanceRepository;

    @Autowired
    private ExecutionAssignmentRepository assignmentRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCompleteInstanceBasedExecutionFlow() {
        UUID adminUserId = createUser("domain-instance-admin").getId();
        WorkDefinitionVersion version = createDefinition(
                "instance-flow",
                baseConfiguration(),
                ResourceRequest.of(2048, 2, false),
                adminUserId
        );
        WorkInstance instance = instanceManagementService.createInstance(new CreateWorkInstanceCommand(
                version.getId(),
                "Instance Flow",
                overridesConfiguration(),
                ResourceRequestOverrides.of(null, 4, true)
        ));
        Worker worker = createWorker("domain-instance");

        WorkExecution execution = creationService.createInstanceExecution(new CreateInstanceExecutionCommand(
                instance.getId()
        ));
        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.AUTO,
                BASE_TIME.plusMinutes(1)
        );
        lifecycleService.markClaimed(execution.getId(), BASE_TIME.plusMinutes(2));
        lifecycleService.markRunning(execution.getId(), BASE_TIME.plusMinutes(3));

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(running -> {
                    assertThat(running.getStatus()).isEqualTo(WorkExecutionStatus.RUNNING);
                    assertThat(running.getInstance()).isNotNull();
                    assertThat(running.getInstance().getId()).isEqualTo(instance.getId());
                    assertThat(running.getDefinitionVersion().getId()).isEqualTo(version.getId());
                    assertMergedInstanceSnapshot(running.getResolvedConfigurationSnapshot());
                    assertThat(running.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(2048, 4, true));
                });
        assertThat(assignmentRepository.findByExecution(execution))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.getId()).isEqualTo(assignment.getId());
                    assertThat(stored.getExecution().getId()).isEqualTo(execution.getId());
                    assertThat(stored.getWorker().getId()).isEqualTo(worker.getId());
                    assertThat(stored.getAssignmentMode()).isEqualTo(ExecutionAssignmentMode.AUTO);
                });
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getAssignment().getId()).isEqualTo(assignment.getId());
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
                    assertThat(attempt.getStartedAt()).isEqualTo(BASE_TIME.plusMinutes(3));
                });

        lifecycleService.markSucceeded(execution.getId(), BASE_TIME.plusMinutes(4));

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(completed -> {
                    assertThat(completed.getStatus()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
                    assertThat(completed.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
                    assertThat(completed.getFailureCode()).isNull();
                    assertThat(completed.getInstance().getId()).isEqualTo(instance.getId());
                });
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
                    assertThat(attempt.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(4));
                    assertThat(attempt.getFailureCode()).isNull();
                });
        assertThat(countAttempts(execution.getId())).isEqualTo(1);
    }

    @Test
    void shouldFailOneOffExecutionFlow() {
        UUID adminUserId = createUser("domain-one-off-admin").getId();
        WorkDefinitionVersion version = createDefinition(
                "one-off-flow",
                baseConfiguration(),
                ResourceRequest.of(1024, 2, false),
                adminUserId
        );
        Worker worker = createWorker("domain-one-off");

        WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                version.getId(),
                oneOffOverridesConfiguration(),
                ResourceRequestOverrides.of(4096, null, true)
        ));
        ExecutionAssignment assignment = assignmentService.assignExecution(
                execution.getId(),
                worker.getId(),
                ExecutionAssignmentMode.REQUIRE,
                BASE_TIME.plusMinutes(11)
        );
        lifecycleService.markClaimed(execution.getId(), BASE_TIME.plusMinutes(12));
        lifecycleService.markRunning(execution.getId(), BASE_TIME.plusMinutes(13));

        assertThat(instanceRepository.findByDefinitionVersion(version)).isEmpty();
        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(running -> {
                    assertThat(running.getStatus()).isEqualTo(WorkExecutionStatus.RUNNING);
                    assertThat(running.getInstance()).isNull();
                    assertThat(running.getDefinitionVersion().getId()).isEqualTo(version.getId());
                    assertMergedOneOffSnapshot(running.getResolvedConfigurationSnapshot());
                    assertThat(running.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(4096, 2, true));
                });
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> assertRunningAttempt(attempt, assignment));

        lifecycleService.markFailed(
                execution.getId(),
                "EXECUTOR_ERROR",
                "Process exited with status 1",
                BASE_TIME.plusMinutes(14)
        );

        assertThat(executionRepository.findById(execution.getId()))
                .hasValueSatisfying(failed -> {
                    assertThat(failed.getStatus()).isEqualTo(WorkExecutionStatus.FAILED);
                    assertThat(failed.getInstance()).isNull();
                    assertThat(failed.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(14));
                    assertThat(failed.getFailureCode()).isEqualTo("EXECUTOR_ERROR");
                    assertThat(failed.getFailureMessage()).isEqualTo("Process exited with status 1");
                    assertMergedOneOffSnapshot(failed.getResolvedConfigurationSnapshot());
                    assertThat(failed.getResolvedResourceRequest()).isEqualTo(ResourceRequest.of(4096, 2, true));
                });
        assertThat(attemptRepository.findByExecution(execution))
                .hasValueSatisfying(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
                    assertThat(attempt.getCompletedAt()).isEqualTo(BASE_TIME.plusMinutes(14));
                    assertThat(attempt.getFailureCode()).isEqualTo("EXECUTOR_ERROR");
                    assertThat(attempt.getFailureMessage()).isEqualTo("Process exited with status 1");
                });
        assertThat(instanceRepository.findByDefinitionVersion(version)).isEmpty();
        assertThat(countAttempts(execution.getId())).isEqualTo(1);
    }

    private WorkDefinitionVersion createDefinition(String suffix,
                                                   JsonNode executorConfiguration,
                                                   ResourceRequest defaultResourceRequest,
                                                   UUID actorUserId) {
        return definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                "localhive.integration-" + suffix + "-" + UUID.randomUUID(),
                WorkType.TASK,
                "Integration Definition",
                null,
                "localhive.integration-executor",
                1,
                executorConfiguration,
                defaultResourceRequest,
                actorUserId
        ));
    }

    private Worker createWorker(String suffix) {
        return workerRepository.save(Worker.builder()
                .hostname("worker-" + suffix + "-" + UUID.randomUUID())
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

    private long countAttempts(UUID executionId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from execution_attempts where execution_id = ?",
                Long.class,
                executionId
        );
        return count == null ? 0 : count;
    }

    private static void assertMergedInstanceSnapshot(JsonNode snapshot) {
        assertThat(snapshot.at("/limits/threads").intValue()).isEqualTo(4);
        assertThat(snapshot.at("/limits/batch").intValue()).isEqualTo(100);
        assertThat(snapshot.get("array").size()).isEqualTo(1);
        assertThat(snapshot.get("array").get(0).intValue()).isEqualTo(3);
        assertThat(snapshot.get("mode").textValue()).isEqualTo("base");
        assertThat(snapshot.get("explicitNull").isNull()).isTrue();
    }

    private static void assertMergedOneOffSnapshot(JsonNode snapshot) {
        assertThat(snapshot.at("/limits/threads").intValue()).isEqualTo(8);
        assertThat(snapshot.at("/limits/batch").intValue()).isEqualTo(100);
        assertThat(snapshot.get("array").size()).isEqualTo(2);
        assertThat(snapshot.get("array").get(0).intValue()).isEqualTo(5);
        assertThat(snapshot.get("array").get(1).intValue()).isEqualTo(8);
        assertThat(snapshot.get("mode").textValue()).isEqualTo("override");
        assertThat(snapshot.get("explicitNull").isNull()).isTrue();
    }

    private static void assertRunningAttempt(ExecutionAttempt attempt, ExecutionAssignment assignment) {
        assertThat(attempt.getAssignment().getId()).isEqualTo(assignment.getId());
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
        assertThat(attempt.getStartedAt()).isEqualTo(BASE_TIME.plusMinutes(13));
        assertThat(attempt.getCompletedAt()).isNull();
    }

    private static ObjectNode baseConfiguration() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode limits = JsonNodeFactory.instance.objectNode();
        limits.put("threads", 2);
        limits.put("batch", 100);
        root.set("limits", limits);
        root.putArray("array").add(1).add(2);
        root.put("mode", "base");
        return root;
    }

    private static ObjectNode overridesConfiguration() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode limits = JsonNodeFactory.instance.objectNode();
        limits.put("threads", 4);
        root.set("limits", limits);
        root.putArray("array").add(3);
        root.set("explicitNull", JsonNodeFactory.instance.nullNode());
        return root;
    }

    private static ObjectNode oneOffOverridesConfiguration() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode limits = JsonNodeFactory.instance.objectNode();
        limits.put("threads", 8);
        root.set("limits", limits);
        root.putArray("array").add(5).add(8);
        root.put("mode", "override");
        root.set("explicitNull", JsonNodeFactory.instance.nullNode());
        return root;
    }
}
