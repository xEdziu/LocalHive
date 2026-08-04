package dev.adrian.goral.localhivebackend.service.research;

import dev.adrian.goral.localhivebackend.domain.research.ResearchOperation;

record ResearchOperationDescriptor(
        ResearchOperation operation,
        String description,
        boolean mutating,
        String resultType
) {
}
