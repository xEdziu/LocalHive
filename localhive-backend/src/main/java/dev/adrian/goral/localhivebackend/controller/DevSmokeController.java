package dev.adrian.goral.localhivebackend.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
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
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import dev.adrian.goral.localhivebackend.service.work.CreateOneOffExecutionCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionContentCommand;
import dev.adrian.goral.localhivebackend.service.work.DefinitionManagementService;
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
import java.util.Set;
import java.util.UUID;

@Profile("dev")
@RestController
@RequestMapping("/api/dev/smoke")
@RequiredArgsConstructor
public class DevSmokeController {

    private static final String NO_OP_LOGICAL_IDENTIFIER = "localhive.no-op";
    private static final String NO_OP_NAME = "NO_OP";
    private static final String NO_OP_EXECUTOR_ID = "localhive.no-op";
    private static final int NO_OP_EXECUTOR_CONTRACT_VERSION = 1;
    private static final String DOCKER_LOGICAL_IDENTIFIER = "localhive.docker.workload";
    private static final String DOCKER_NAME = "Docker Workload";
    private static final String DOCKER_DESCRIPTION = "Run a controlled Docker workload on an Agent.";
    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";
    private static final int DOCKER_EXECUTOR_CONTRACT_VERSION = 1;
    private static final String DEFAULT_DOCKER_IMAGE = "alpine:3.20";
    private static final List<String> DEFAULT_DOCKER_COMMAND = List.of(
            "sh",
            "-c",
            "echo LocalHive Docker workload"
    );
    private static final int DEFAULT_DOCKER_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_DOCKER_MEMORY_MB = 128;
    private static final int DEFAULT_DOCKER_CPU_CORES = 1;
    private static final Set<String> DOCKER_IMAGE_ALLOWLIST = Set.of(DEFAULT_DOCKER_IMAGE);

    private final DefinitionManagementService definitionManagementService;
    private final WorkExecutionCreationService creationService;
    private final WorkExecutionAssignmentService assignmentService;
    private final WorkDefinitionRepository definitionRepository;
    private final WorkDefinitionVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final ArtifactRepository artifactRepository;

    @PostMapping("/workers/{workerId}/no-op")
    public ResponseEntity<NoOpSmokeResponseDto> seedNoOpExecution(@PathVariable UUID workerId,
                                                                  Authentication authentication) {
        try {
            WorkDefinitionVersion noOpVersion = findOrCreateNoOpVersion(authentication);
            WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                    noOpVersion.getId(),
                    JsonNodeFactory.instance.objectNode(),
                    ResourceRequestOverrides.empty()
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
            @RequestBody(required = false) DockerWorkloadSmokeRequestDto request,
            Authentication authentication
    ) {
        DockerWorkloadSmokeRequestDto workloadRequest = validateDockerWorkloadRequest(request);
        try {
            WorkDefinitionVersion dockerVersion = findOrCreateDockerVersion(authentication);
            WorkExecution execution = creationService.createOneOffExecution(new CreateOneOffExecutionCommand(
                    dockerVersion.getId(),
                    dockerConfiguration(workloadRequest),
                    ResourceRequestOverrides.of(
                            workloadRequest.resources().memoryMb(),
                            workloadRequest.resources().cpuCores(),
                            false
                    )
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
                        defaultDockerConfiguration(),
                        ResourceRequest.of(DEFAULT_DOCKER_MEMORY_MB, DEFAULT_DOCKER_CPU_CORES, false),
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

    private static ObjectNode defaultDockerConfiguration() {
        return dockerConfiguration(defaultDockerWorkloadRequest());
    }

    private static ObjectNode dockerConfiguration(DockerWorkloadSmokeRequestDto request) {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("image", request.image());

        ArrayNode command = configuration.putArray("command");
        request.command().forEach(command::add);

        configuration.put("timeoutSeconds", request.timeoutSeconds());

        ObjectNode resources = configuration.putObject("resources");
        resources.put("memoryMb", request.resources().memoryMb());
        resources.put("cpuCores", request.resources().cpuCores());

        ObjectNode gpu = configuration.putObject("gpu");
        gpu.put("required", false);

        if (request.workspace() != null) {
            ObjectNode workspace = configuration.putObject("workspace");
            workspace.put("artifactId", request.workspace().artifactId().toString());
            workspace.put("mountPath", request.workspace().mountPath());
            workspace.put("readOnly", request.workspace().readOnly());
        }

        return configuration;
    }

    private DockerWorkloadSmokeRequestDto validateDockerWorkloadRequest(
            DockerWorkloadSmokeRequestDto request
    ) {
        DockerWorkloadSmokeRequestDto candidate = request == null ? defaultDockerWorkloadRequest() : request;
        String image = requireAllowedImage(candidate.image());
        List<String> command = requireCommand(candidate.command());
        int timeoutSeconds = requireRange(candidate.timeoutSeconds(), "timeoutSeconds", 1, 300);
        DockerWorkloadResourcesDto resources = candidate.resources();
        if (resources == null) {
            throw badRequest("resources is required.");
        }
        int memoryMb = requireRange(resources.memoryMb(), "resources.memoryMb", 16, 4096);
        int cpuCores = requireRange(resources.cpuCores(), "resources.cpuCores", 1, 8);

        DockerWorkloadGpuDto gpu = candidate.gpu();
        if (gpu == null || gpu.required() == null) {
            throw badRequest("gpu.required is required.");
        }
        if (gpu.required()) {
            throw badRequest("gpu.required must be false. GPU workloads are deferred.");
        }
        DockerWorkloadWorkspaceDto workspace = validateWorkspace(candidate.workspace());

        return new DockerWorkloadSmokeRequestDto(
                image,
                command,
                timeoutSeconds,
                new DockerWorkloadResourcesDto(memoryMb, cpuCores),
                new DockerWorkloadGpuDto(false),
                workspace
        );
    }

    private static DockerWorkloadSmokeRequestDto defaultDockerWorkloadRequest() {
        return new DockerWorkloadSmokeRequestDto(
                DEFAULT_DOCKER_IMAGE,
                DEFAULT_DOCKER_COMMAND,
                DEFAULT_DOCKER_TIMEOUT_SECONDS,
                new DockerWorkloadResourcesDto(DEFAULT_DOCKER_MEMORY_MB, DEFAULT_DOCKER_CPU_CORES),
                new DockerWorkloadGpuDto(false),
                null
        );
    }

    private DockerWorkloadWorkspaceDto validateWorkspace(DockerWorkloadWorkspaceDto workspace) {
        if (workspace == null) {
            return null;
        }
        if (workspace.artifactId() == null) {
            throw badRequest("workspace.artifactId is required.");
        }
        artifactRepository.findById(workspace.artifactId())
                .filter(artifact -> artifact.getKind() == ArtifactKind.WORKSPACE_PACKAGE)
                .orElseThrow(() -> badRequest(
                        "workspace.artifactId must reference an existing WORKSPACE_PACKAGE artifact."
                ));
        if (!"/workspace".equals(workspace.mountPath())) {
            throw badRequest("workspace.mountPath must be /workspace.");
        }
        if (!Boolean.TRUE.equals(workspace.readOnly())) {
            throw badRequest("workspace.readOnly must be true.");
        }

        return new DockerWorkloadWorkspaceDto(workspace.artifactId(), "/workspace", true);
    }

    private static String requireAllowedImage(String image) {
        if (image == null || image.isBlank()) {
            throw badRequest("image is required.");
        }
        String normalizedImage = image.trim();
        if (!DOCKER_IMAGE_ALLOWLIST.contains(normalizedImage)) {
            throw badRequest("image is not allowlisted.");
        }
        return normalizedImage;
    }

    private static List<String> requireCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw badRequest("command must be a non-empty array.");
        }

        return command.stream()
                .map(element -> {
                    if (element == null || element.isBlank()) {
                        throw badRequest("command elements must not be blank.");
                    }
                    return element.trim();
                })
                .toList();
    }

    private static int requireRange(Integer value, String fieldName, int min, int max) {
        if (value == null) {
            throw badRequest(fieldName + " is required.");
        }
        if (value < min || value > max) {
            throw badRequest(fieldName + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record NoOpSmokeResponseDto(
            UUID executionId,
            UUID workerId,
            String status,
            String executorId,
            int executorContractVersion
    ) {
    }

    public record DockerWorkloadSmokeRequestDto(
            String image,
            List<String> command,
            Integer timeoutSeconds,
            DockerWorkloadResourcesDto resources,
            DockerWorkloadGpuDto gpu,
            DockerWorkloadWorkspaceDto workspace
    ) {
    }

    public record DockerWorkloadResourcesDto(
            Integer memoryMb,
            Integer cpuCores
    ) {
    }

    public record DockerWorkloadGpuDto(
            Boolean required
    ) {
    }

    public record DockerWorkloadWorkspaceDto(
            UUID artifactId,
            String mountPath,
            Boolean readOnly
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
