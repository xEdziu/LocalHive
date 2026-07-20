package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.WorkDefinition;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkDefinitionRepository extends JpaRepository<WorkDefinition, UUID> {

    Optional<WorkDefinition> findByLogicalIdentifier(String logicalIdentifier);

    boolean existsByLogicalIdentifier(String logicalIdentifier);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WorkDefinition d where d.logicalIdentifier = :logicalIdentifier")
    Optional<WorkDefinition> findByLogicalIdentifierForUpdate(@Param("logicalIdentifier") String logicalIdentifier);

    @Query("""
            SELECT definition
            FROM WorkDefinition definition
            WHERE (:type IS NULL OR definition.workType = :type)
              AND (:logicalId IS NULL OR definition.logicalIdentifier = :logicalId)
            ORDER BY definition.logicalIdentifier ASC
            """)
    List<WorkDefinition> findAdminDefinitions(
            @Param("type") WorkType type,
            @Param("logicalId") String logicalId,
            Pageable pageable
    );

    @Query("""
            SELECT COUNT(definition)
            FROM WorkDefinition definition
            WHERE (:type IS NULL OR definition.workType = :type)
              AND (:logicalId IS NULL OR definition.logicalIdentifier = :logicalId)
            """)
    long countAdminDefinitions(
            @Param("type") WorkType type,
            @Param("logicalId") String logicalId
    );
}
