package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionAssignment;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
import dev.adrian.goral.localhivebackend.service.work.DockerWorkloadConfiguration;
import dev.adrian.goral.localhivebackend.service.work.DockerWorkloadConfigurationValidator;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionAssignmentService;
import dev.adrian.goral.localhivebackend.service.work.WorkExecutionCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Profile("dev")
@RestController
@RequestMapping("/api/dev/smoke")
@RequiredArgsConstructor
public class DevSmokeController {

    private static final String NO_OP_LOGICAL_IDENTIFIER = "localhive.no-op";
    private static final String NO_OP_NAME = "NO_OP";
    private static final String NO_OP_EXECUTOR_ID = "localhive.no-op";
    private static final String NO_OP_DISPLAY_NAME = "NO-OP smoke test";
    private static final int NO_OP_EXECUTOR_CONTRACT_VERSION = 1;
    private static final String DOCKER_LOGICAL_IDENTIFIER = "localhive.docker.workload";
    private static final String DOCKER_NAME = "Docker Workload";
    private static final String DOCKER_DESCRIPTION = "Run a controlled Docker workload on an Agent.";
    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";
    private static final int DOCKER_EXECUTOR_CONTRACT_VERSION = 1;

    private final DefinitionManagementService definitionManagementService;
    private final WorkExecutionCreationService creationService;
    private final WorkExecutionAssignmentService assignmentService;
    private final WorkDefinitionRepository definitionRepository;
    private final WorkDefinitionVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final DockerWorkloadConfigurationValidator dockerWorkloadConfigurationValidator;

    @PostMapping("/workers/{workerId}/no-op")
    public ResponseEntity<NoOpSmokeResponseDto> seedNoOpExecution(@PathVariable UUID workerId,
                                                                  Authentication authentication) {
        try {
            WorkDefinitionVersion noOpVersion = findOrCreateNoOpVersion(authentication);
            WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                    noOpVersion.getId(),
                    JsonNodeFactory.instance.objectNode(),
                    ResourceRequestOverrides.empty(),
                    NO_OP_DISPLAY_NAME
            ));
            ExecutionAssignment assignment = assignmentService.assignExecution(
                    execution.getId(),
                    workerId,
                    ExecutionAssignmentMode.REQUIRE,
                    LocalDateTime.now()
            );

            return ResponseEntity.ok(new NoOpSmokeResponseDto(
                    assignment.getExecution().getId(),
                    assignment.getWorker().getId(),
                    assignment.getExecution().getStatus().name(),
                    noOpVersion.getExecutorId(),
                    noOpVersion.getExecutorContractVersion()
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/workers/{workerId}/docker-workload")
    public ResponseEntity<DockerWorkloadSmokeResponseDto> seedDockerWorkloadExecution(
            @PathVariable UUID workerId,
            @RequestBody(required = false) DockerWorkloadConfiguration.Request request,
            Authentication authentication
    ) {
        DockerWorkloadConfiguration.Validated workloadRequest;
        try {
            workloadRequest = dockerWorkloadConfigurationValidator.validateSmokeRequest(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        try {
            WorkDefinitionVersion dockerVersion = findOrCreateDockerVersion(authentication);
            WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                    dockerVersion.getId(),
                    dockerWorkloadConfigurationValidator.toConfiguration(workloadRequest),
                    ResourceRequestOverrides.of(
                            workloadRequest.memoryMb(),
                            workloadRequest.cpuCores(),
                            false
                    ),
                    workloadRequest.displayName()
            ));
            ExecutionAssignment assignment = assignmentService.assignExecution(
                    execution.getId(),
                    workerId,
                    ExecutionAssignmentMode.REQUIRE,
                    LocalDateTime.now()
            );

            return ResponseEntity.ok(new DockerWorkloadSmokeResponseDto(
                    assignment.getExecution().getId(),
                    assignment.getWorker().getId(),
                    assignment.getExecution().getStatus().name(),
                    dockerVersion.getExecutorId(),
                    dockerVersion.getExecutorContractVersion(),
                    workloadRequest.image(),
                    workloadRequest.timeoutSeconds()
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private WorkDefinitionVersion findOrCreateNoOpVersion(Authentication authentication) {
        return definitionRepository.findByLogicalIdentifier(NO_OP_LOGICAL_IDENTIFIER)
                .map(this::findApprovedNoOpVersion)
                .orElseGet(() -> definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                        NO_OP_LOGICAL_IDENTIFIER,
                        WorkType.TASK,
                        NO_OP_NAME,
                        null,
                        NO_OP_EXECUTOR_ID,
                        NO_OP_EXECUTOR_CONTRACT_VERSION,
                        noOpConfiguration(),
                        ResourceRequest.zero(),
                        resolveActorUserId(authentication)
                )));
    }

    private WorkDefinitionVersion findOrCreateDockerVersion(Authentication authentication) {
        return definitionRepository.findByLogicalIdentifier(DOCKER_LOGICAL_IDENTIFIER)
                .map(this::findApprovedDockerVersion)
                .orElseGet(() -> definitionManagementService.createLocalDefinition(new DefinitionContentCommand(
                        DOCKER_LOGICAL_IDENTIFIER,
                        WorkType.TASK,
                        DOCKER_NAME,
                        DOCKER_DESCRIPTION,
                        DOCKER_EXECUTOR_ID,
                        DOCKER_EXECUTOR_CONTRACT_VERSION,
                        dockerWorkloadConfigurationValidator.defaultConfiguration(),
                        ResourceRequest.of(
                                DockerWorkloadConfigurationValidator.DEFAULT_DOCKER_MEMORY_MB,
                                DockerWorkloadConfigurationValidator.DEFAULT_DOCKER_CPU_CORES,
                                false
                        ),
                        resolveActorUserId(authentication)
                )));
    }

    private WorkDefinitionVersion findApprovedNoOpVersion(WorkDefinition definition) {
        if (definition.getWorkType() != WorkType.TASK) {
            throw new IllegalStateException("NO_OP work definition must have TASK work type.");
        }

        return versionRepository.findByDefinitionOrderByVersionNumberAsc(definition).stream()
                .filter(version -> version.getApprovalStatus() == DefinitionApprovalStatus.APPROVED)
                .filter(DevSmokeController::hasNoOpExecutorContract)
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new IllegalStateException("Approved NO_OP definition version not found."));
    }

    private WorkDefinitionVersion findApprovedDockerVersion(WorkDefinition definition) {
        if (definition.getWorkType() != WorkType.TASK) {
            throw new IllegalStateException("Docker workload definition must have TASK work type.");
        }

        return versionRepository.findByDefinitionOrderByVersionNumberAsc(definition).stream()
                .filter(version -> version.getApprovalStatus() == DefinitionApprovalStatus.APPROVED)
                .filter(DevSmokeController::hasDockerExecutorContract)
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new IllegalStateException(
                        "Approved Docker workload definition version not found."
                ));
    }

    private UUID resolveActorUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }

        return userRepository.findAll(PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "At least one user is required to create the dev NO_OP definition."
                ));
    }

    private static boolean hasNoOpExecutorContract(WorkDefinitionVersion version) {
        return NO_OP_EXECUTOR_ID.equals(version.getExecutorId())
                && version.getExecutorContractVersion() == NO_OP_EXECUTOR_CONTRACT_VERSION;
    }

    private static boolean hasDockerExecutorContract(WorkDefinitionVersion version) {
        return DOCKER_EXECUTOR_ID.equals(version.getExecutorId())
                && version.getExecutorContractVersion() == DOCKER_EXECUTOR_CONTRACT_VERSION;
    }

    private static ObjectNode noOpConfiguration() {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("message", "noop");
        return configuration;
    }

    public record NoOpSmokeResponseDto(
            UUID executionId,
            UUID workerId,
            String status,
            String executorId,
            int executorContractVersion
    ) {
    }

    public record DockerWorkloadSmokeResponseDto(
            UUID executionId,
            UUID workerId,
            String status,
            String executorId,
            int executorContractVersion,
            String image,
            int timeoutSeconds
    ) {
    }
}
