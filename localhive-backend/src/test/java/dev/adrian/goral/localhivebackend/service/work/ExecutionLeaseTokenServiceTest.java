package dev.adrian.goral.localhivebackend.service.work;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionLeaseTokenServiceTest {

    private final ExecutionLeaseTokenService tokenService =
            new ExecutionLeaseTokenService(new BCryptPasswordEncoder());

    @Test
    void shouldGenerateNonBlankUniqueTokens() {
        String first = tokenService.generateToken();
        String second = tokenService.generateToken();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldHashAndVerifyTokenWithoutStoringRawValue() {
        String rawToken = tokenService.generateToken();

        String hash = tokenService.hashToken(rawToken);

        assertThat(hash).isNotBlank();
        assertThat(hash).isNotEqualTo(rawToken);
        assertThat(tokenService.matches(rawToken, hash)).isTrue();
        assertThat(tokenService.matches("wrong-token", hash)).isFalse();
    }

    @Test
    void shouldRejectBlankTokenAndHashInputs() {
        assertThatThrownBy(() -> tokenService.hashToken(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenService.matches(" ", "$2a$10$hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenService.matches("token", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
