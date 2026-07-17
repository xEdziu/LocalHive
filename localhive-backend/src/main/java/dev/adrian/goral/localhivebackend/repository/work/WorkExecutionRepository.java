package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkExecutionRepository extends JpaRepository<WorkExecution, UUID> {

    List<WorkExecution> findByStatus(WorkExecutionStatus status);

    List<WorkExecution> findByInstance(WorkInstance instance);

    List<WorkExecution> findByDefinitionVersion(WorkDefinitionVersion definitionVersion);
}
