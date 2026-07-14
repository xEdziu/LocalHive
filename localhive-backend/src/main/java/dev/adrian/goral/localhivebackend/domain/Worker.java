package dev.adrian.goral.localhivebackend.domain;

import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "workers")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String hostname;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "os_type", nullable = false)
    private String osType;

    @Column(name = "total_ram_mb", nullable = false)
    private Integer totalRamMb;

    @Column(name = "shared_ram_mb", nullable = false)
    private Integer sharedRamMb;

    @Column(name = "cpu_cores", nullable = false)
    private Integer cpuCores;

    @Column(name = "gpu_name")
    private String gpuName;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private WorkerApprovalStatus approvalStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false)
    private WorkerConnectionStatus connectionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false)
    private WorkerAvailabilityStatus availabilityStatus;

    @Column(name = "api_key_hash")
    private String apiKeyHash;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        Worker worker = (Worker) o;
        return getId() != null && Objects.equals(getId(), worker.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
