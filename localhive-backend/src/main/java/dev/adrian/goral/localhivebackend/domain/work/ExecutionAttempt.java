package dev.adrian.goral.localhivebackend.domain.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.ExecutionAttemptStatus;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkExecutionStatus;
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
        name = "execution_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_execution_attempts_execution_id",
                        columnNames = "execution_id"
                ),
                @UniqueConstraint(
                        name = "uk_execution_attempts_execution_attempt_number",
                        columnNames = {"execution_id", "attempt_number"}
                )
        }
)
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    @ToString.Exclude
    private WorkExecution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    @ToString.Exclude
    private ExecutionAssignment assignment;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionAttemptStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    private ExecutionAttempt(WorkExecution execution,
                             ExecutionAssignment assignment,
                             int attemptNumber,
                             LocalDateTime startedAt) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null.");
        requireRunningExecution(execution);
        this.assignment = Objects.requireNonNull(assignment, "assignment must not be null.");
        requireAssignmentMatchesExecution(execution, assignment);
        this.attemptNumber = requireFirstAttempt(attemptNumber);
        this.status = ExecutionAttemptStatus.RUNNING;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null.");
    }

    public static ExecutionAttempt createRunning(WorkExecution execution,
                                                 ExecutionAssignment assignment,
                                                 int attemptNumber,
                                                 LocalDateTime startedAt) {
        return new ExecutionAttempt(execution, assignment, attemptNumber, startedAt);
    }

    public void markSucceeded(LocalDateTime completedAt) {
        requireStatus(ExecutionAttemptStatus.RUNNING, "mark attempt as succeeded");
        this.completedAt = requireTimestamp(completedAt, "completedAt");
        this.status = ExecutionAttemptStatus.SUCCEEDED;
    }

    public void markFailed(String failureCode, String failureMessage, LocalDateTime completedAt) {
        requireStatus(ExecutionAttemptStatus.RUNNING, "mark attempt as failed");
        LocalDateTime failureCompletedAt = requireTimestamp(completedAt, "completedAt");
        String validFailureCode = requireNonBlank(failureCode, "failureCode");
        String validFailureMessage = requireNullableNonBlank(failureMessage, "failureMessage");

        this.completedAt = failureCompletedAt;
        this.failureCode = validFailureCode;
        this.failureMessage = validFailureMessage;
        this.status = ExecutionAttemptStatus.FAILED;
    }

    public void markCancelled(LocalDateTime completedAt) {
        requireStatus(ExecutionAttemptStatus.RUNNING, "mark attempt as cancelled");
        this.completedAt = requireTimestamp(completedAt, "completedAt");
        this.status = ExecutionAttemptStatus.CANCELLED;
    }

    private void requireStatus(ExecutionAttemptStatus expectedStatus, String action) {
        if (status != expectedStatus) {
            throw new IllegalStateException("Cannot " + action + " from status " + status + ".");
        }
    }

    private static void requireRunningExecution(WorkExecution execution) {
        if (execution.getStatus() != WorkExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "Execution attempt can be created only for RUNNING execution. Current status: "
                            + execution.getStatus()
            );
        }
    }

    private static void requireAssignmentMatchesExecution(WorkExecution execution, ExecutionAssignment assignment) {
        WorkExecution assignmentExecution = assignment.getExecution();
        boolean sameExecution = execution == assignmentExecution
                || execution.getId() != null && execution.getId().equals(assignmentExecution.getId());

        if (!sameExecution) {
            throw new IllegalArgumentException("assignment must belong to the attempt execution.");
        }
    }

    private static int requireFirstAttempt(int attemptNumber) {
        if (attemptNumber != 1) {
            throw new IllegalArgumentException("attemptNumber must be 1.");
        }

        return attemptNumber;
    }

    private static LocalDateTime requireTimestamp(LocalDateTime timestamp, String fieldName) {
        return Objects.requireNonNull(timestamp, fieldName + " must not be null.");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        return value;
    }

    private static String requireNullableNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank when present.");
        }

        return value;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        ExecutionAttempt that = (ExecutionAttempt) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
