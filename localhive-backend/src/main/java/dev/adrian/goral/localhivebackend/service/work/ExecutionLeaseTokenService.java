package dev.adrian.goral.localhivebackend.service.work;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class ExecutionLeaseTokenService {

    private static final int TOKEN_BYTES = 32;

    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hashToken(String rawToken) {
        return passwordEncoder.encode(requireNonBlank(rawToken, "rawToken"));
    }

    public boolean matches(String rawToken, String storedHash) {
        return passwordEncoder.matches(
                requireNonBlank(rawToken, "rawToken"),
                requireNonBlank(storedHash, "storedHash")
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }

        return value;
    }
}
