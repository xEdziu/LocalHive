package dev.adrian.goral.localhivebackend.service.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class RestResearchOperationInvoker implements ResearchProtocolOperationInvoker {

    private final ResearchProtocolReadModelService readModelService;
    private final ObjectMapper objectMapper;

    RestResearchOperationInvoker(ResearchProtocolReadModelService readModelService, ObjectMapper objectMapper) {
        this.readModelService = readModelService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public ResearchProtocol protocol() {
        return ResearchProtocol.REST;
    }

    @Override
    public ResearchProtocolInvocationResult invoke(ResearchOperation operation, UUID executionGroupId) {
        long startedAt = System.nanoTime();
        Object payload = readModelService.read(operation, executionGroupId);
        byte[] responseBytes = jsonBytes(payload);
        return new ResearchProtocolInvocationResult(
                protocol(),
                operation,
                elapsedMillis(startedAt),
                responseBytes.length
        );
    }

    private byte[] jsonBytes(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("REST response serialization failed.", e);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
