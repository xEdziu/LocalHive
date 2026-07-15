package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.regex.Pattern;

public final class DefinitionValidation {

    private static final Pattern LOGICAL_IDENTIFIER_PATTERN = Pattern.compile(
            "^[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+$"
    );
    private static final Pattern EXECUTOR_ID_PATTERN = Pattern.compile(
            "^[a-z0-9]+(?:[.-][a-z0-9]+)+$"
    );

    private DefinitionValidation() {
    }

    public static String requireValidLogicalIdentifier(String logicalIdentifier) {
        String normalized = requireNonBlank(logicalIdentifier, "logicalIdentifier").trim();

        if (!LOGICAL_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "logicalIdentifier must contain lowercase ASCII letters, digits, dots and hyphens."
            );
        }

        return normalized;
    }

    public static String requireValidExecutorId(String executorId) {
        String normalized = requireNonBlank(executorId, "executorId").trim();

        if (!EXECUTOR_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("executorId must be a namespaced lowercase identifier.");
        }

        return normalized;
    }

    public static String requireNonBlankName(String name) {
        return requireNonBlank(name, "name");
    }

    public static void requirePositiveExecutorContractVersion(int executorContractVersion) {
        if (executorContractVersion < 1) {
            throw new IllegalArgumentException("executorContractVersion must be greater than or equal to 1.");
        }
    }

    public static JsonNode requireObjectConfiguration(JsonNode executorConfiguration) {
        if (executorConfiguration == null || !executorConfiguration.isObject()) {
            throw new IllegalArgumentException("executorConfiguration must be a non-null JSON object.");
        }

        return executorConfiguration;
    }

    public static UUID requireUserId(UUID userId, String fieldName) {
        if (userId == null) {
            throw new IllegalArgumentException(fieldName + " must not be null.");
        }

        return userId;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        return value;
    }
}
