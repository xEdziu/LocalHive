package dev.adrian.goral.localhivebackend.domain;

import dev.adrian.goral.localhivebackend.domain.enums.CommandStatus;
import dev.adrian.goral.localhivebackend.domain.enums.CommandType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "agent_commands")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // we use LAZY, so we don't fetch the whole worker with all its commands when we load a command, a common pitfall with EAGER
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    @ToString.Exclude // exclude from toString to avoid potential infinite recursion when printing
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false)
    private CommandType commandType;

    // Mapping of native JSONB in Hibernate 6
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        AgentCommand that = (AgentCommand) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}