package dev.adrian.goral.localhivebackend.repository.research;

import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRun;
import dev.adrian.goral.localhivebackend.domain.research.BenchmarkRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, UUID> {

    @Query("""
            SELECT run
            FROM BenchmarkRun run
            WHERE (:status IS NULL OR run.status = :status)
            ORDER BY run.createdAt DESC, run.id DESC
            """)
    List<BenchmarkRun> findAdminRuns(@Param("status") BenchmarkRunStatus status);
}
