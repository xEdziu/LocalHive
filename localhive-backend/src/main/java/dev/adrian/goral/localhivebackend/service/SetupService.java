package dev.adrian.goral.localhivebackend.service;

import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    // In-memory cache.
    // Null = unknown (needs DB check), False = system is fully configured.
    private volatile Boolean isConfiguredCache = null;

    /**
     * Checks if the system requires initial setup.
     * Implements Double-Checked Locking (DCL) for maximum thread safety and performance.
     */
    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        // Fast path (Lock-free): If already configured, return immediately
        if (Boolean.FALSE.equals(isConfiguredCache)) {
            return false;
        }

        // Slow path: Synchronized block to prevent multiple threads hitting the DB at the exact same millisecond
        synchronized (this) {
            // Double-check inside the lock (in case another thread just finished the setup)
            if (Boolean.FALSE.equals(isConfiguredCache)) {
                return false;
            }

            boolean required = userRepository.count() == 0;

            if (!required) {
                isConfiguredCache = false;
                log.info("System configuration verified. Caching state permanently.");
            } else {
                log.info("System is currently unconfigured. Awaiting First-Time Setup.");
            }

            return required;
        }
    }

    /**
     * Completes the First-Time Config by creating the initial Admin user.
     *
     * @param username the admin username
     * @param rawPassword the raw password (will be hashed)
     */
    @Transactional
    public void completeFirstTimeSetup(String username, String rawPassword) {
        if (!isSetupRequired()) {
            throw new IllegalStateException("System is already configured. Cannot run setup again.");
        }

        String securedHash = passwordEncoder.encode(rawPassword);

        User adminUser = User.builder()
                .username(username)
                .passwordHash(securedHash)
                .build();

        userRepository.save(adminUser);

        // Force cache invalidation immediately so the very next request is allowed through
        isConfiguredCache = false;

        log.info("First-Time Setup completed successfully. Admin user '{}' has been created.", username);
    }
}