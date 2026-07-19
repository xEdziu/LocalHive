package dev.adrian.goral.localhivebackend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.service.work.ClaimedExecution;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkerExecutionClaimResponseDto(
        UUID executionId,
        String displayName,
        String executorId,
        int executorContractVersion,
        Map<String, Object> configuration,
        int requiredRamMb,
        int requiredCpuCores,
        boolean gpuRequired,
        String leaseToken,
        LocalDateTime leaseExpiresAt
) {

    public static WorkerExecutionClaimResponseDto from(ClaimedExecution claimedExecution) {
        WorkExecution execution = claimedExecution.execution();
        WorkDefinitionVersion definitionVersion = execution.getDefinitionVersion();
        ResourceRequest resourceRequest = execution.getResolvedResourceRequest();
        return new WorkerExecutionClaimResponseDto(
                execution.getId(),
                execution.getDisplayNameSnapshot(),
                definitionVersion.getExecutorId(),
                definitionVersion.getExecutorContractVersion(),
                toJsonObject(execution.getResolvedConfigurationSnapshot()),
                resourceRequest.getRequiredRamMb(),
                resourceRequest.getRequiredCpuCores(),
                resourceRequest.isGpuRequired(),
                claimedExecution.rawLeaseToken(),
                claimedExecution.assignment().getLeaseExpiresAt()
        );
    }

    private static Map<String, Object> toJsonObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("configuration must be a non-null JSON object.");
        }

        Map<String, Object> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> values.put(entry.getKey(), toJsonValue(entry.getValue())));
        return values;
    }

    private static Object toJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            return toJsonObject(node);
        }

        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(element -> values.add(toJsonValue(element)));
            return values;
        }

        if (node.isTextual()) {
            return node.textValue();
        }

        if (node.isBoolean()) {
            return node.booleanValue();
        }

        if (node.isIntegralNumber()) {
            if (node.canConvertToInt()) {
                return node.intValue();
            }
            if (node.canConvertToLong()) {
                return node.longValue();
            }
            return node.bigIntegerValue();
        }

        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return node.decimalValue();
        }

        return node.numberValue();
    }
}
