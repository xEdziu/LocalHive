package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionGroupStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExecutionGroupRepository extends JpaRepository<ExecutionGroup, UUID> {

    @Query("""
            SELECT executionGroup
            FROM ExecutionGroup executionGroup
            WHERE (:status IS NULL OR executionGroup.status = :status)
            ORDER BY executionGroup.createdAt DESC, executionGroup.id DESC
            """)
    List<ExecutionGroup> findAdminGroups(
            @Param("status") ExecutionGroupStatus status,
            Pageable pageable
    );

    @Query("""
            SELECT COUNT(executionGroup)
            FROM ExecutionGroup executionGroup
            WHERE (:status IS NULL OR executionGroup.status = :status)
            """)
    long countAdminGroups(@Param("status") ExecutionGroupStatus status);
}
