package dev.adrian.goral.localhivebackend.domain;

import dev.adrian.goral.localhivebackend.domain.enums.ServerState;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "server_instances")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    @ToString.Exclude
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    @ToString.Exclude
    private GameTemplate template;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "allocated_ram_mb", nullable = false)
    private Integer allocatedRamMb;

    @Column(name = "assigned_port", nullable = false)
    private Integer assignedPort;

    @Column(name = "container_id")
    private String containerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_state", nullable = false)
    private ServerState desiredState;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_state", nullable = false)
    private ServerState actualState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_env_vars", columnDefinition = "jsonb")
    private Map<String, Object> customEnvVars;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        ServerInstance that = (ServerInstance) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}