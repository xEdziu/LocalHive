package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;
import dev.adrian.goral.localhivebackend.service.work.AdminExecutionGroupQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class ResearchProtocolReadModelService {

    private final AdminExecutionGroupQueryService queryService;

    Object read(ResearchOperation operation, UUID executionGroupId) {
        return switch (operation) {
            case GET_GROUP_DETAIL -> queryService.getGroup(executionGroupId)
                    .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
            case GET_GROUP_ACTIVITY -> queryService.getGroupActivity(executionGroupId)
                    .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
            case GET_GROUP_ARTIFACTS -> queryService.listGroupArtifacts(executionGroupId)
                    .orElseThrow(() -> new NoSuchElementException("Execution group not found."));
            default -> throw new IllegalArgumentException("Unsupported protocol comparison operation.");
        };
    }
}
