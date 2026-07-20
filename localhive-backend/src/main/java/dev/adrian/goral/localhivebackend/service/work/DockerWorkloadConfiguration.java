package dev.adrian.goral.localhivebackend.service.work;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DockerWorkloadConfiguration {

    private DockerWorkloadConfiguration() {
    }

    public record Request(
            String image,
            List<String> command,
            Integer timeoutSeconds,
            Resources resources,
            Gpu gpu,
            Workspace workspace,
            String displayName
    ) {
    }

    public record Resources(
            Integer memoryMb,
            Integer cpuCores
    ) {
    }

    public record Gpu(
            Boolean required
    ) {
    }

    public record Workspace(
            UUID artifactId,
            String mountPath,
            Boolean readOnly
    ) {
    }

    public record Validated(
            String image,
            List<String> command,
            int timeoutSeconds,
            int memoryMb,
            int cpuCores,
            Workspace workspace,
            String displayName
    ) {

        public Validated {
            image = Objects.requireNonNull(image, "image must not be null.");
            command = List.copyOf(Objects.requireNonNull(command, "command must not be null."));
        }
    }
}
