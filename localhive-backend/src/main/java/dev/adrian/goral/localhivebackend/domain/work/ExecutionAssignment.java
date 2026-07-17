package dev.adrian.goral.localhivebackend.domain.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAssignmentMode;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "execution_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_execution_assignments_execution_id",
                columnNames = "execution_id"
        ),
        indexes = @Index(name = "idx_execution_assignments_worker_id", columnList = "worker_id")
)
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    @ToString.Exclude
    private WorkExecution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false, updatable = false)
    @ToString.Exclude
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_mode", nullable = false, updatable = false)
    private ExecutionAssignmentMode assignmentMode;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

    private ExecutionAssignment(WorkExecution execution,
                                Worker worker,
                                ExecutionAssignmentMode assignmentMode,
                                LocalDateTime assignedAt) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null.");
        requireAssignedExecution(execution);
        this.worker = Objects.requireNonNull(worker, "worker must not be null.");
        this.assignmentMode = Objects.requireNonNull(assignmentMode, "assignmentMode must not be null.");
        this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt must not be null.");
    }

    public static ExecutionAssignment create(WorkExecution execution,
                                             Worker worker,
                                             ExecutionAssignmentMode assignmentMode,
                                             LocalDateTime assignedAt) {
        return new ExecutionAssignment(execution, worker, assignmentMode, assignedAt);
    }

    private static void requireAssignedExecution(WorkExecution execution) {
        if (execution.getStatus() != WorkExecutionStatus.ASSIGNED) {
            throw new IllegalStateException(
                    "Execution assignment can be created only for ASSIGNED execution. Current status: "
                            + execution.getStatus()
            );
        }
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        ExecutionAssignment that = (ExecutionAssignment) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
