package dev.adrian.goral.localhivebackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSetting {

    @Id
    @Column(name = "config_key", nullable = false, updatable = false)
    private String configKey;

    @Column(name = "config_value", nullable = false)
    private String configValue;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        SystemSetting that = (SystemSetting) o;
        return getConfigKey() != null && Objects.equals(getConfigKey(), that.getConfigKey());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
