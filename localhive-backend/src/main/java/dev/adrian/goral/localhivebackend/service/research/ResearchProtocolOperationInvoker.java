package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.domain.research.ResearchProtocol;

import java.util.UUID;

public interface ResearchProtocolOperationInvoker {

    ResearchProtocol protocol();

    ResearchProtocolInvocationResult invoke(ResearchOperation operation, UUID executionGroupId);
}
