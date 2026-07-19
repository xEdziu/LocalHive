package dev.adrian.goral.localhivebackend.domain.work;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Objects;

public final class WorkExecutionDisplayName {

    public static final int MAX_LENGTH = 255;

    private static final String NO_OP_EXECUTOR_ID = "localhive.no-op";
    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";
    private static final String DEFAULT_NO_OP_DISPLAY_NAME = "NO-OP smoke test";
    private static final String DEFAULT_DOCKER_DISPLAY_NAME = "Docker workload";
    private static final String DEFAULT_DISPLAY_NAME = "Work execution";

    private WorkExecutionDisplayName() {
    }

    public static String resolve(String explicitDisplayName,
                                 WorkDefinitionVersion definitionVersion,
                                 WorkInstance instance,
                                 JsonNode resolvedConfigurationSnapshot) {
        WorkDefinitionVersion validDefinitionVersion = Objects.requireNonNull(
                definitionVersion,
                "definitionVersion must not be null."
        );

        String explicit = normalizeOptional(explicitDisplayName);
        if (explicit != null) {
            return requireMaxLength(explicit);
        }

        String instanceDisplayName = instance == null ? null : normalizeOptional(instance.getDisplayName());
        if (instanceDisplayName != null) {
            return requireMaxLength(instanceDisplayName);
        }

        String executorId = validDefinitionVersion.getExecutorId();
        if (NO_OP_EXECUTOR_ID.equals(executorId)) {
            return DEFAULT_NO_OP_DISPLAY_NAME;
        }
        if (DOCKER_EXECUTOR_ID.equals(executorId)) {
            return requireMaxLength(dockerDisplayName(resolvedConfigurationSnapshot));
        }

        return requireMaxLength(humanizedIdentifier(validDefinitionVersion));
    }

    public static String validateExplicit(String displayName) {
        String normalized = normalizeOptional(displayName);
        return normalized == null ? null : requireMaxLength(normalized);
    }

    private static String dockerDisplayName(JsonNode resolvedConfigurationSnapshot) {
        String image = null;
        if (resolvedConfigurationSnapshot != null && resolvedConfigurationSnapshot.isObject()) {
            JsonNode imageNode = resolvedConfigurationSnapshot.get("image");
            if (imageNode != null && imageNode.isTextual()) {
                image = normalizeOptional(imageNode.textValue());
            }
        }

        return image == null ? DEFAULT_DOCKER_DISPLAY_NAME : DEFAULT_DOCKER_DISPLAY_NAME + ": " + image;
    }

    private static String humanizedIdentifier(WorkDefinitionVersion definitionVersion) {
        String source = normalizeOptional(definitionVersion.getExecutorId());
        if (source == null && definitionVersion.getDefinition() != null) {
            source = normalizeOptional(definitionVersion.getDefinition().getLogicalIdentifier());
        }
        if (source == null) {
            return DEFAULT_DISPLAY_NAME;
        }

        String[] segments = source.split("\\.");
        int start = segments.length > 1 && "localhive".equals(segments[0]) ? 1 : 0;
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < segments.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(segments[i].replace('-', ' '));
        }

        String normalized = normalizeOptional(builder.toString());
        if (normalized == null) {
            return DEFAULT_DISPLAY_NAME;
        }

        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireMaxLength(String value) {
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName must be less than or equal to " + MAX_LENGTH + " characters."
            );
        }

        return value;
    }
}
