package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinitionVersion;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.domain.work.WorkInstance;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "instance"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            JOIN ExecutionAssignment assignment ON assignment.execution = execution
            WHERE assignment.worker.id = :workerId
              AND execution.status IN :statuses
            ORDER BY execution.createdAt DESC, execution.id DESC
            """)
    List<WorkExecution> findAdminExecutionsByWorkerIdAndStatusIn(
            @Param("workerId") UUID workerId,
            @Param("statuses") Collection<WorkExecutionStatus> statuses,
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

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "instance", "executionGroup"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            WHERE execution.executionGroup.id = :executionGroupId
            ORDER BY CASE
                         WHEN execution.groupRole = dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole.SHARD THEN 0
                         WHEN execution.groupRole = dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole.MERGE THEN 1
                         ELSE 2
                     END ASC,
                     CASE WHEN execution.shardIndex IS NULL THEN 1 ELSE 0 END ASC,
                     execution.shardIndex ASC,
                     execution.createdAt ASC,
                     execution.id ASC
            """)
    List<WorkExecution> findAdminExecutionsByExecutionGroupId(
            @Param("executionGroupId") UUID executionGroupId
    );

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "executionGroup"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            WHERE execution.executionGroup.id = :executionGroupId
              AND execution.groupRole = dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole.SHARD
              AND execution.status = dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus.QUEUED
            ORDER BY execution.shardIndex ASC, execution.createdAt ASC, execution.id ASC
            """)
    List<WorkExecution> findQueuedShardExecutionsByExecutionGroupId(
            @Param("executionGroupId") UUID executionGroupId
    );

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "executionGroup"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            WHERE execution.executionGroup.id = :executionGroupId
              AND execution.groupRole = :groupRole
            ORDER BY execution.createdAt ASC, execution.id ASC
            """)
    List<WorkExecution> findByExecutionGroupIdAndGroupRole(
            @Param("executionGroupId") UUID executionGroupId,
            @Param("groupRole") WorkExecutionGroupRole groupRole
    );

    @EntityGraph(attributePaths = {"definitionVersion", "definitionVersion.definition", "executionGroup"})
    @Query("""
            SELECT execution
            FROM WorkExecution execution
            WHERE execution.executionGroup.id = :executionGroupId
              AND execution.groupRole = dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionGroupRole.MERGE
              AND execution.status = dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus.QUEUED
            ORDER BY execution.createdAt ASC, execution.id ASC
            """)
    List<WorkExecution> findQueuedMergeExecutionsByExecutionGroupId(
            @Param("executionGroupId") UUID executionGroupId
    );

    @Query("""
            SELECT execution.executionGroup.id AS executionGroupId,
                   execution.status AS status,
                   COUNT(execution) AS executionCount
            FROM WorkExecution execution
            WHERE execution.executionGroup.id IN :executionGroupIds
            GROUP BY execution.executionGroup.id, execution.status
            """)
    List<ExecutionGroupStatusCountProjection> countStatusesByExecutionGroupIds(
            @Param("executionGroupIds") Collection<UUID> executionGroupIds
    );

    @Query("""
            SELECT execution.status AS status,
                   COUNT(execution) AS executionCount
            FROM WorkExecution execution
            WHERE execution.executionGroup.id = :executionGroupId
            GROUP BY execution.status
            """)
    List<ExecutionStatusCountProjection> countStatusesByExecutionGroupId(
            @Param("executionGroupId") UUID executionGroupId
    );

    long countByExecutionGroup_Id(UUID executionGroupId);

    interface ExecutionGroupStatusCountProjection {

        UUID getExecutionGroupId();

        WorkExecutionStatus getStatus();

        Long getExecutionCount();
    }

    interface ExecutionStatusCountProjection {

        WorkExecutionStatus getStatus();

        Long getExecutionCount();
    }
}
