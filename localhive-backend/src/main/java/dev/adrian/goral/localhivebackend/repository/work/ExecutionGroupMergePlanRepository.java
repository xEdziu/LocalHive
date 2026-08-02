package dev.adrian.goral.localhivebackend.repository.work;

import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroupMergePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ExecutionGroupMergePlanRepository extends JpaRepository<ExecutionGroupMergePlan, UUID> {
}
