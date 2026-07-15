package dev.adrian.goral.localhivebackend.domain.work;

import dev.adrian.goral.localhivebackend.domain.work.enums.DefinitionSourceType;
import dev.adrian.goral.localhivebackend.domain.work.enums.WorkType;
import dev.adrian.goral.localhivebackend.service.work.DefinitionValidation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "work_definitions")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "logical_identifier", nullable = false, unique = true, updatable = false)
    private String logicalIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false, updatable = false)
    private WorkType workType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private DefinitionSourceType sourceType;

    @Column(name = "original_definition_id", updatable = false)
    private String originalDefinitionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private WorkDefinition(String logicalIdentifier,
                           WorkType workType,
                           DefinitionSourceType sourceType,
                           String originalDefinitionId,
                           LocalDateTime createdAt) {
        this.logicalIdentifier = DefinitionValidation.requireValidLogicalIdentifier(logicalIdentifier);
        this.workType = Objects.requireNonNull(workType, "workType must not be null.");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null.");
        this.originalDefinitionId = originalDefinitionId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
    }

    public static WorkDefinition createLocal(String logicalIdentifier, WorkType workType, LocalDateTime createdAt) {
        return new WorkDefinition(logicalIdentifier, workType, DefinitionSourceType.LOCAL, null, createdAt);
    }

    public static WorkDefinition createImported(String logicalIdentifier,
                                                WorkType workType,
                                                String originalDefinitionId,
                                                LocalDateTime createdAt) {
        return new WorkDefinition(
                logicalIdentifier,
                workType,
                DefinitionSourceType.IMPORTED,
                originalDefinitionId,
                createdAt
        );
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        WorkDefinition that = (WorkDefinition) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
