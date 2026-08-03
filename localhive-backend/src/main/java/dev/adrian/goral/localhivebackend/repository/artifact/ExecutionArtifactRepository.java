package dev.adrian.goral.localhivebackend.repository.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
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
public interface ExecutionArtifactRepository extends JpaRepository<ExecutionArtifact, UUID> {

    @EntityGraph(attributePaths = {"artifact", "execution", "uploadedByWorker"})
    List<ExecutionArtifact> findByExecution_IdAndArtifact_KindOrderByCreatedAtAsc(UUID executionId, ArtifactKind kind);

    @EntityGraph(attributePaths = {"artifact", "execution", "uploadedByWorker"})
    List<ExecutionArtifact> findByExecution_IdInAndArtifact_Kind(Collection<UUID> executionIds, ArtifactKind kind);

    @EntityGraph(attributePaths = {"artifact", "execution", "uploadedByWorker"})
    Optional<ExecutionArtifact> findByArtifact_Id(UUID artifactId);

    long countByExecution_IdAndArtifact_Kind(UUID executionId, ArtifactKind kind);

    @Query("""
            SELECT executionArtifact.execution.id AS executionId,
                   COUNT(executionArtifact) AS artifactCount
            FROM ExecutionArtifact executionArtifact
            WHERE executionArtifact.execution.id IN :executionIds
              AND executionArtifact.artifact.kind = :kind
            GROUP BY executionArtifact.execution.id
            """)
    List<ExecutionArtifactCountProjection> countByExecutionIdsAndArtifactKind(
            @Param("executionIds") Collection<UUID> executionIds,
            @Param("kind") ArtifactKind kind
    );

    interface ExecutionArtifactCountProjection {
        UUID getExecutionId();

        Long getArtifactCount();
    }
}
