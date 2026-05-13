package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.AgentCommand;
import dev.adrian.goral.localhivebackend.domain.enums.CommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentCommandRepository extends JpaRepository<AgentCommand, UUID> {

    // We look for commands for specific agent with specific status (e.g. QUEUED)
    List<AgentCommand> findAllByWorkerIdAndStatusOrderByCreatedAtAsc(UUID workerId, CommandStatus status);

    Optional<AgentCommand> findFirstByWorkerIdAndStatusOrderByCreatedAtAsc(UUID workerId, CommandStatus status);
}