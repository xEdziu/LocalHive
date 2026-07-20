package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecutionDisplayName;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DockerWorkloadConfigurationValidator {

    public static final String DEFAULT_DOCKER_IMAGE = "alpine:3.20";
    public static final List<String> DEFAULT_DOCKER_COMMAND = List.of(
            "sh",
            "-c",
            "echo LocalHive Docker workload"
    );
    public static final int DEFAULT_DOCKER_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_DOCKER_MEMORY_MB = 128;
    public static final int DEFAULT_DOCKER_CPU_CORES = 1;

    private static final Set<String> DOCKER_IMAGE_ALLOWLIST = Set.of(DEFAULT_DOCKER_IMAGE);

    private final ArtifactRepository artifactRepository;

    public DockerWorkloadConfiguration.Validated validateSmokeRequest(DockerWorkloadConfiguration.Request request) {
        DockerWorkloadConfiguration.Request candidate = request == null ? defaultRequest() : request;
        return validate(candidate);
    }

    public DockerWorkloadConfiguration.Validated validateAdminConfiguration(JsonNode configuration) {
        if (configuration == null || configuration.isNull()) {
            throw new IllegalArgumentException("configuration is required for localhive.docker.workload.");
        }
        if (!configuration.isObject()) {
            throw new IllegalArgumentException("configuration must be a JSON object.");
        }

        return validate(readRequest(configuration));
    }

    public ObjectNode defaultConfiguration() {
        return toConfiguration(validate(defaultRequest()));
    }

    public ObjectNode toConfiguration(DockerWorkloadConfiguration.Validated request) {
        ObjectNode configuration = JsonNodeFactory.instance.objectNode();
        configuration.put("image", request.image());

        ArrayNode command = configuration.putArray("command");
        request.command().forEach(command::add);

        configuration.put("timeoutSeconds", request.timeoutSeconds());

        ObjectNode resources = configuration.putObject("resources");
        resources.put("memoryMb", request.memoryMb());
        resources.put("cpuCores", request.cpuCores());

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

    private DockerWorkloadConfiguration.Validated validate(DockerWorkloadConfiguration.Request candidate) {
        String image = requireAllowedImage(candidate.image());
        List<String> command = requireCommand(candidate.command());
        int timeoutSeconds = requireRange(candidate.timeoutSeconds(), "timeoutSeconds", 1, 300);

        DockerWorkloadConfiguration.Resources resources = candidate.resources();
        if (resources == null) {
            throw new IllegalArgumentException("resources is required.");
        }
        int memoryMb = requireRange(resources.memoryMb(), "resources.memoryMb", 16, 4096);
        int cpuCores = requireRange(resources.cpuCores(), "resources.cpuCores", 1, 8);

        DockerWorkloadConfiguration.Gpu gpu = candidate.gpu();
        if (gpu == null || gpu.required() == null) {
            throw new IllegalArgumentException("gpu.required is required.");
        }
        if (gpu.required()) {
            throw new IllegalArgumentException("gpu.required must be false. GPU workloads are deferred.");
        }

        DockerWorkloadConfiguration.Workspace workspace = validateWorkspace(candidate.workspace());
        String displayName = validateDisplayName(candidate.displayName());

        return new DockerWorkloadConfiguration.Validated(
                image,
                command,
                timeoutSeconds,
                memoryMb,
                cpuCores,
                workspace,
                displayName
        );
    }

    private DockerWorkloadConfiguration.Request defaultRequest() {
        return new DockerWorkloadConfiguration.Request(
                DEFAULT_DOCKER_IMAGE,
                DEFAULT_DOCKER_COMMAND,
                DEFAULT_DOCKER_TIMEOUT_SECONDS,
                new DockerWorkloadConfiguration.Resources(DEFAULT_DOCKER_MEMORY_MB, DEFAULT_DOCKER_CPU_CORES),
                new DockerWorkloadConfiguration.Gpu(false),
                null,
                null
        );
    }

    private DockerWorkloadConfiguration.Request readRequest(JsonNode configuration) {
        return new DockerWorkloadConfiguration.Request(
                readText(configuration, "image"),
                readStringArray(configuration.get("command")),
                readInteger(configuration, "timeoutSeconds"),
                readResources(configuration.get("resources")),
                readGpu(configuration.get("gpu")),
                readWorkspace(configuration.get("workspace")),
                null
        );
    }

    private DockerWorkloadConfiguration.Resources readResources(JsonNode resources) {
        if (resources == null || resources.isNull()) {
            return null;
        }
        if (!resources.isObject()) {
            throw new IllegalArgumentException("resources must be a JSON object.");
        }

        return new DockerWorkloadConfiguration.Resources(
                readInteger(resources, "memoryMb"),
                readInteger(resources, "cpuCores")
        );
    }

    private DockerWorkloadConfiguration.Gpu readGpu(JsonNode gpu) {
        if (gpu == null || gpu.isNull()) {
            return null;
        }
        if (!gpu.isObject()) {
            throw new IllegalArgumentException("gpu must be a JSON object.");
        }

        return new DockerWorkloadConfiguration.Gpu(readBoolean(gpu, "required"));
    }

    private DockerWorkloadConfiguration.Workspace readWorkspace(JsonNode workspace) {
        if (workspace == null || workspace.isNull()) {
            return null;
        }
        if (!workspace.isObject()) {
            throw new IllegalArgumentException("workspace must be a JSON object.");
        }

        return new DockerWorkloadConfiguration.Workspace(
                readUuid(workspace, "artifactId"),
                readText(workspace, "mountPath"),
                readBoolean(workspace, "readOnly")
        );
    }

    private DockerWorkloadConfiguration.Workspace validateWorkspace(DockerWorkloadConfiguration.Workspace workspace) {
        if (workspace == null) {
            return null;
        }
        if (workspace.artifactId() == null) {
            throw new IllegalArgumentException("workspace.artifactId is required.");
        }
        artifactRepository.findById(workspace.artifactId())
                .filter(artifact -> artifact.getKind() == ArtifactKind.WORKSPACE_PACKAGE)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workspace.artifactId must reference an existing WORKSPACE_PACKAGE artifact."
                ));
        if (!"/workspace".equals(workspace.mountPath())) {
            throw new IllegalArgumentException("workspace.mountPath must be /workspace.");
        }
        if (!Boolean.TRUE.equals(workspace.readOnly())) {
            throw new IllegalArgumentException("workspace.readOnly must be true.");
        }

        return new DockerWorkloadConfiguration.Workspace(workspace.artifactId(), "/workspace", true);
    }

    private static String requireAllowedImage(String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image is required.");
        }
        String normalizedImage = image.trim();
        if (!DOCKER_IMAGE_ALLOWLIST.contains(normalizedImage)) {
            throw new IllegalArgumentException("image is not allowlisted.");
        }
        return normalizedImage;
    }

    private static List<String> requireCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must be a non-empty array.");
        }

        return command.stream()
                .map(element -> {
                    if (element == null || element.isBlank()) {
                        throw new IllegalArgumentException("command elements must not be blank.");
                    }
                    return element.trim();
                })
                .toList();
    }

    private static int requireRange(Integer value, String fieldName, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private static String validateDisplayName(String displayName) {
        return WorkExecutionDisplayName.validateExplicit(displayName);
    }

    private static String readText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(fieldName + " must be a text value.");
        }

        return value.textValue();
    }

    private static Integer readInteger(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(fieldName + " must be a whole number.");
        }

        return value.intValue();
    }

    private static Boolean readBoolean(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(fieldName + " must be a boolean.");
        }

        return value.booleanValue();
    }

    private static UUID readUuid(JsonNode object, String fieldName) {
        String value = readText(object, fieldName);
        if (value == null) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID.");
        }
    }

    private static List<String> readStringArray(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("command must be a non-empty array.");
        }

        List<String> values = new ArrayList<>();
        value.forEach(element -> {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("command elements must be text values.");
            }
            values.add(element.textValue());
        });
        return values;
    }
}
