package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.ServerInstance;
import dev.adrian.goral.localhivebackend.domain.enums.ServerState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServerInstanceRepository extends JpaRepository<ServerInstance, UUID> {

    List<ServerInstance> findAllByWorkerId(UUID workerId);
    List<ServerInstance> findAllByWorkerIdAndDesiredState(UUID workerId, ServerState state);
}