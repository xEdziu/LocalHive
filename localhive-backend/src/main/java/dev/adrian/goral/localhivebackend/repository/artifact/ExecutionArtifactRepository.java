package dev.adrian.goral.localhivebackend.repository.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExecutionArtifactRepository extends JpaRepository<ExecutionArtifact, UUID> {

    @EntityGraph(attributePaths = {"artifact", "execution", "uploadedByWorker"})
    List<ExecutionArtifact> findByExecution_IdAndArtifact_KindOrderByCreatedAtAsc(UUID executionId, ArtifactKind kind);

    @EntityGraph(attributePaths = {"artifact", "execution", "uploadedByWorker"})
    Optional<ExecutionArtifact> findByArtifact_Id(UUID artifactId);
}
