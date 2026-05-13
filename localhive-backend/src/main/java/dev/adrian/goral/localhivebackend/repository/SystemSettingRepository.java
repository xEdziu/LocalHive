package dev.adrian.goral.localhivebackend.repository;

import dev.adrian.goral.localhivebackend.domain.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
    List<SystemSetting> findAllByConfigKeyStartingWith(String prefix);
}