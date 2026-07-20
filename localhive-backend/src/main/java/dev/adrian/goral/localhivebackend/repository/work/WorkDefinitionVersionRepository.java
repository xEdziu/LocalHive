package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkDefinitionVersionRepository extends JpaRepository<WorkDefinitionVersion, UUID> {

    @Query("""
            select coalesce(max(v.versionNumber), 0)
            from WorkDefinitionVersion v
            where v.definition = :definition
            """)
    int findHighestVersionNumber(@Param("definition") WorkDefinition definition);

    List<WorkDefinitionVersion> findByDefinitionOrderByVersionNumberAsc(WorkDefinition definition);

    List<WorkDefinitionVersion> findByDefinitionOrderByVersionNumberDesc(WorkDefinition definition);

    List<WorkDefinitionVersion> findByDefinition_IdIn(Collection<UUID> definitionIds);

    Optional<WorkDefinitionVersion> findByDefinitionAndVersionNumber(WorkDefinition definition, int versionNumber);
}
