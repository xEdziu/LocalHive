package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.GameTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GameTemplateRepository extends JpaRepository<GameTemplate, UUID> {
    List<GameTemplate> findByNameContainingIgnoreCase(String text);
}