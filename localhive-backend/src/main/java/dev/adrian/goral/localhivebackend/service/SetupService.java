package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    private final UserRepository userRepository;

    /**
     * Checks if the system requires initial setup.
     * The system is considered unconfigured if there is no admin user in the database.
     *
     * @return true if setup is needed, false otherwise.
     */
    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        boolean required = userRepository.count() == 0;
        if (required) {
            log.info("Missing configuration detected. System requires First-Time Config initialization.");
        }
        return required;
    }
}