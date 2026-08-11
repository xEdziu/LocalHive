package dev.adrian.goral.localhivebackend.domain.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "benchmark_events")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkEvent {

    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_METADATA_JSON_LENGTH = 8000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "benchmark_event_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "benchmark_run_id", nullable = false, updatable = false)
    @ToString.Exclude
    private BenchmarkRun benchmarkRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benchmark_scenario_id", updatable = false)
    @ToString.Exclude
    private BenchmarkScenario benchmarkScenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private BenchmarkEventType type;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "related_execution_id")
    private UUID relatedExecutionId;

    @Column(name = "related_execution_group_id")
    private UUID relatedExecutionGroupId;

    private BenchmarkEvent(BenchmarkRun benchmarkRun,
                           BenchmarkScenario benchmarkScenario,
                           BenchmarkEventType type,
                           String message,
                           String metadataJson,
                           UUID relatedExecutionId,
                           UUID relatedExecutionGroupId,
                           LocalDateTime occurredAt) {
        this.benchmarkRun = Objects.requireNonNull(benchmarkRun, "benchmarkRun must not be null.");
        this.benchmarkScenario = benchmarkScenario;
        this.type = Objects.requireNonNull(type, "type must not be null.");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null.");
        this.message = requireMessage(message);
        this.metadataJson = optionalMetadataJson(metadataJson);
        this.relatedExecutionId = relatedExecutionId;
        this.relatedExecutionGroupId = relatedExecutionGroupId;
    }

    public static BenchmarkEvent create(BenchmarkRun benchmarkRun,
                                        BenchmarkScenario benchmarkScenario,
                                        BenchmarkEventType type,
                                        String message,
                                        String metadataJson,
                                        UUID relatedExecutionId,
                                        UUID relatedExecutionGroupId,
                                        LocalDateTime occurredAt) {
        return new BenchmarkEvent(
                benchmarkRun,
                benchmarkScenario,
                type,
                message,
                metadataJson,
                relatedExecutionId,
                relatedExecutionGroupId,
                occurredAt
        );
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message must not be blank.");
        }

        String trimmed = value.trim();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must not be longer than 2000 characters.");
        }

        return trimmed;
    }

    private static String optionalMetadataJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() > MAX_METADATA_JSON_LENGTH) {
            throw new IllegalArgumentException("metadataJson must not be longer than 8000 characters.");
        }

        return trimmed;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        BenchmarkEvent that = (BenchmarkEvent) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
