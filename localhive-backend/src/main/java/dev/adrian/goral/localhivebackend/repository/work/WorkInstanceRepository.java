package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkInstanceRepository extends JpaRepository<WorkInstance, UUID> {

    List<WorkInstance> findByDefinitionVersion(WorkDefinitionVersion definitionVersion);

    List<WorkInstance> findByDefinitionVersionAndEnabledTrue(WorkDefinitionVersion definitionVersion);
}
