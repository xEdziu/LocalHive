package dev.adrian.goral.localhivebackend.repository.artifact;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {

    List<Artifact> findByKind(ArtifactKind kind);

    List<Artifact> findBySha256(String sha256);
}
