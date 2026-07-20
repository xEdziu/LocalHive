package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkExecutionRepository extends JpaRepository<WorkExecution, UUID> {

    List<WorkExecution> findByStatus(WorkExecutionStatus status);

    List<WorkExecution> findByInstance(WorkInstance instance);

    List<WorkExecution> findByDefinitionVersion(WorkDefinitionVersion definitionVersion);

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "instance"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            LEFT JOIN ExecutionAssignment assignment ON assignment.execution = execution
            WHERE (:status IS NULL OR execution.status = :status)
              AND (:workerId IS NULL OR assignment.worker.id = :workerId)
            ORDER BY execution.createdAt DESC, execution.id DESC
            """)
    List<WorkExecution> findAdminExecutions(
            @Param("status") WorkExecutionStatus status,
            @Param("workerId") UUID workerId,
            Pageable pageable
    );

    @Query("""
            SELECT COUNT(DISTINCT execution)
            FROM WorkExecution execution
            LEFT JOIN ExecutionAssignment assignment ON assignment.execution = execution
            WHERE (:status IS NULL OR execution.status = :status)
              AND (:workerId IS NULL OR assignment.worker.id = :workerId)
            """)
    long countAdminExecutions(
            @Param("status") WorkExecutionStatus status,
            @Param("workerId") UUID workerId
    );

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "instance"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            WHERE execution.id = :executionId
            """)
    Optional<WorkExecution> findAdminExecutionById(@Param("executionId") UUID executionId);
}
