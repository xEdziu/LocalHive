package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.dto.AdminWorkDefinitionDetailResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminWorkDefinitionListResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminWorkDefinitionSummaryResponseDto;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionRepository;
import dev.adrian.goral.localhivebackend.repository.work.WorkDefinitionVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminWorkDefinitionQueryService {

    private final WorkDefinitionRepository definitionRepository;
    private final WorkDefinitionVersionRepository versionRepository;

    @Transactional(readOnly = true)
    public AdminWorkDefinitionListResponseDto listDefinitions(
            int limit,
            int offset,
            WorkType type,
            String logicalId
    ) {
        int validLimit = requireValidLimit(limit);
        int validOffset = requireValidOffset(offset);
        String validLogicalId = normalizeLogicalId(logicalId);

        long totalCount = definitionRepository.countAdminDefinitions(type, validLogicalId);
        List<WorkDefinition> definitions = definitionRepository.findAdminDefinitions(
                type,
                validLogicalId,
                new OffsetLimitPageRequest(validOffset, validLimit)
        );
        if (definitions.isEmpty()) {
            return new AdminWorkDefinitionListResponseDto(List.of(), validLimit, validOffset, totalCount);
        }

        Map<UUID, List<WorkDefinitionVersion>> versionsByDefinitionId = versionsByDefinitionId(definitions);
        List<AdminWorkDefinitionSummaryResponseDto> items = definitions.stream()
                .map(definition -> toSummary(
                        definition,
                        versionsByDefinitionId.getOrDefault(definition.getId(), List.of())
                ))
                .toList();
        return new AdminWorkDefinitionListResponseDto(items, validLimit, validOffset, totalCount);
    }

    @Transactional(readOnly = true)
    public Optional<AdminWorkDefinitionDetailResponseDto> getDefinition(UUID definitionId) {
        UUID validDefinitionId = Objects.requireNonNull(definitionId, "definitionId must not be null.");
        return definitionRepository.findById(validDefinitionId)
                .map(definition -> toDetail(
                        definition,
                        versionRepository.findByDefinitionOrderByVersionNumberDesc(definition)
                ));
    }

    private static int requireValidLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200.");
        }

        return limit;
    }

    private static int requireValidOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0.");
        }

        return offset;
    }

    private static String normalizeLogicalId(String logicalId) {
        if (logicalId == null) {
            return null;
        }
        if (logicalId.isBlank()) {
            throw new IllegalArgumentException("logicalId must not be blank.");
        }

        return logicalId.trim();
    }

    private Map<UUID, List<WorkDefinitionVersion>> versionsByDefinitionId(List<WorkDefinition> definitions) {
        List<UUID> definitionIds = definitions.stream()
                .map(WorkDefinition::getId)
                .toList();
        return versionRepository.findByDefinition_IdIn(definitionIds)
                .stream()
                .collect(Collectors.groupingBy(version -> version.getDefinition().getId()));
    }

    private static AdminWorkDefinitionSummaryResponseDto toSummary(
            WorkDefinition definition,
            List<WorkDefinitionVersion> versions
    ) {
        WorkDefinitionVersion latestVersion = latestVersion(versions);
        return new AdminWorkDefinitionSummaryResponseDto(
                definition.getId(),
                definition.getLogicalIdentifier(),
                definition.getWorkType().name(),
                definition.getSourceType().name(),
                latestVersion == null ? null : latestVersion.getName(),
                latestVersion == null ? null : latestVersion.getDescription(),
                latestVersion == null ? null : latestVersion.getVersionNumber(),
                versions.size(),
                latestVersion == null ? null : latestVersion.getId(),
                latestVersion == null ? null : latestVersion.getExecutorId(),
                latestVersion == null ? null : latestVersion.getExecutorContractVersion(),
                latestVersion == null ? null : latestVersion.getApprovalStatus().name(),
                definition.getCreatedAt()
        );
    }

    private static AdminWorkDefinitionDetailResponseDto toDetail(
            WorkDefinition definition,
            List<WorkDefinitionVersion> versions
    ) {
        WorkDefinitionVersion latestVersion = versions.isEmpty() ? null : versions.get(0);
        int latestVersionNumber = latestVersion == null ? 0 : latestVersion.getVersionNumber();

        return new AdminWorkDefinitionDetailResponseDto(
                definition.getId(),
                definition.getLogicalIdentifier(),
                definition.getWorkType().name(),
                definition.getSourceType().name(),
                latestVersion == null ? null : latestVersion.getName(),
                latestVersion == null ? null : latestVersion.getDescription(),
                definition.getCreatedAt(),
                versions.stream()
                        .map(version -> toVersion(version, latestVersionNumber))
                        .toList()
        );
    }

    private static AdminWorkDefinitionDetailResponseDto.VersionDto toVersion(
            WorkDefinitionVersion version,
            int latestVersionNumber
    ) {
        return new AdminWorkDefinitionDetailResponseDto.VersionDto(
                version.getId(),
                version.getVersionNumber(),
                version.getVersionNumber() == latestVersionNumber,
                version.getName(),
                version.getDescription(),
                version.getExecutorId(),
                version.getExecutorContractVersion(),
                version.getApprovalStatus().name(),
                version.getCreatedAt()
        );
    }

    private static WorkDefinitionVersion latestVersion(List<WorkDefinitionVersion> versions) {
        return versions.stream()
                .max(Comparator.comparingInt(WorkDefinitionVersion::getVersionNumber))
                .orElse(null);
    }

    private record OffsetLimitPageRequest(int offset, int limit) implements Pageable {

        @Override
        public int getPageNumber() {
            return offset / limit;
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return Sort.unsorted();
        }

        @Override
        public Pageable next() {
            return new OffsetLimitPageRequest(offset + limit, limit);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious()
                    ? new OffsetLimitPageRequest(Math.max(0, offset - limit), limit)
                    : first();
        }

        @Override
        public Pageable first() {
            return new OffsetLimitPageRequest(0, limit);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            if (pageNumber < 0) {
                throw new IllegalArgumentException("pageNumber must be greater than or equal to 0.");
            }

            return new OffsetLimitPageRequest(pageNumber * limit, limit);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }
}
