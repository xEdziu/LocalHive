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
@Table(name = "benchmark_scenarios")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkScenario {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;
    public static final int MAX_WORKLOAD_ID_LENGTH = 100;
    public static final int MAX_ERROR_CODE_LENGTH = 100;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    public static final int MAX_NOTES_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "benchmark_scenario_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "benchmark_run_id", nullable = false, updatable = false)
    @ToString.Exclude
    private BenchmarkRun benchmarkRun;

    @Column(name = "scenario_index", nullable = false)
    private int scenarioIndex;

    @Column(name = "display_name", nullable = false, length = MAX_DISPLAY_NAME_LENGTH)
    private String displayName;

    @Column(name = "workload_id", nullable = false, length = MAX_WORKLOAD_ID_LENGTH)
    private String workloadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ResearchProtocol protocol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private ResearchOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_transfer_mode", nullable = false, length = 100)
    private ResearchDataTransferMode dataTransferMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payload_format", nullable = false, length = 50)
    private ResearchPayloadFormat payloadFormat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BenchmarkScenarioStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "execution_group_id")
    private UUID executionGroupId;

    @Column(name = "error_code", length = MAX_ERROR_CODE_LENGTH)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private BenchmarkScenario(BenchmarkRun benchmarkRun,
                              int scenarioIndex,
                              String displayName,
                              String workloadId,
                              ResearchProtocol protocol,
                              ResearchOperation operation,
                              ResearchDataTransferMode dataTransferMode,
                              ResearchPayloadFormat payloadFormat,
                              String notes,
                              LocalDateTime createdAt) {
        this.benchmarkRun = Objects.requireNonNull(benchmarkRun, "benchmarkRun must not be null.");
        if (scenarioIndex < 0) {
            throw new IllegalArgumentException("scenarioIndex must be greater than or equal to 0.");
        }
        this.scenarioIndex = scenarioIndex;
        this.displayName = requireText(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
        this.workloadId = requireText(workloadId, "workloadId", MAX_WORKLOAD_ID_LENGTH);
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null.");
        this.operation = Objects.requireNonNull(operation, "operation must not be null.");
        this.dataTransferMode = Objects.requireNonNull(dataTransferMode, "dataTransferMode must not be null.");
        this.payloadFormat = Objects.requireNonNull(payloadFormat, "payloadFormat must not be null.");
        this.status = BenchmarkScenarioStatus.CREATED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.notes = optionalText(notes, "notes", MAX_NOTES_LENGTH);
    }

    public static BenchmarkScenario create(BenchmarkRun benchmarkRun,
                                           int scenarioIndex,
                                           String displayName,
                                           String workloadId,
                                           ResearchProtocol protocol,
                                           ResearchOperation operation,
                                           ResearchDataTransferMode dataTransferMode,
                                           ResearchPayloadFormat payloadFormat,
                                           String notes,
                                           LocalDateTime createdAt) {
        return new BenchmarkScenario(
                benchmarkRun,
                scenarioIndex,
                displayName,
                workloadId,
                protocol,
                operation,
                dataTransferMode,
                payloadFormat,
                notes,
                createdAt
        );
    }

    public void start(LocalDateTime startedAt) {
        if (status == BenchmarkScenarioStatus.RUNNING) {
            return;
        }
        requireNonTerminal("start benchmark scenario");

        LocalDateTime timestamp = Objects.requireNonNull(startedAt, "startedAt must not be null.");
        this.status = BenchmarkScenarioStatus.RUNNING;
        this.startedAt = timestamp;
    }

    public void complete(LocalDateTime completedAt) {
        requireNonTerminal("complete benchmark scenario");

        LocalDateTime timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        this.status = BenchmarkScenarioStatus.COMPLETED;
        if (startedAt == null) {
            this.startedAt = timestamp;
        }
        this.completedAt = timestamp;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void fail(String errorCode, String errorMessage, LocalDateTime completedAt) {
        requireNonTerminal("fail benchmark scenario");

        LocalDateTime timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        this.status = BenchmarkScenarioStatus.FAILED;
        if (startedAt == null) {
            this.startedAt = timestamp;
        }
        this.completedAt = timestamp;
        this.errorCode = optionalText(errorCode, "errorCode", MAX_ERROR_CODE_LENGTH);
        this.errorMessage = optionalText(errorMessage, "errorMessage", MAX_ERROR_MESSAGE_LENGTH);
    }

    public void skip(String errorCode, String errorMessage, LocalDateTime completedAt) {
        requireNonTerminal("skip benchmark scenario");

        LocalDateTime timestamp = Objects.requireNonNull(completedAt, "completedAt must not be null.");
        this.status = BenchmarkScenarioStatus.SKIPPED;
        this.completedAt = timestamp;
        this.errorCode = optionalText(errorCode, "errorCode", MAX_ERROR_CODE_LENGTH);
        this.errorMessage = optionalText(errorMessage, "errorMessage", MAX_ERROR_MESSAGE_LENGTH);
    }

    public boolean belongsTo(BenchmarkRun run) {
        return run != null && belongsTo(run.getId());
    }

    public boolean belongsTo(UUID benchmarkRunId) {
        return benchmarkRunId != null
                && benchmarkRun != null
                && benchmarkRunId.equals(benchmarkRun.getId());
    }

    public boolean isTerminal() {
        return status == BenchmarkScenarioStatus.COMPLETED
                || status == BenchmarkScenarioStatus.FAILED
                || status == BenchmarkScenarioStatus.CANCELLED
                || status == BenchmarkScenarioStatus.SKIPPED;
    }

    private void requireNonTerminal(String action) {
        if (isTerminal()) {
            throw new IllegalStateException("Cannot " + action + " from status " + status + ".");
        }
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not be longer than " + maxLength + " characters.");
        }

        return trimmed;
    }

    private static String optionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not be longer than " + maxLength + " characters.");
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
        BenchmarkScenario that = (BenchmarkScenario) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
