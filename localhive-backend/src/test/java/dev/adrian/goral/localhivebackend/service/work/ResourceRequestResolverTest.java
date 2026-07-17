package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceRequestResolverTest {

    private final ResourceRequestResolver resolver = new ResourceRequestResolver();

    @Test
    void shouldResolveDefaultsWhenOverridesAreEmptyOrNull() {
        ResourceRequest defaults = ResourceRequest.of(4096, 4, true);

        assertThat(resolver.resolve(defaults, ResourceRequestOverrides.empty()))
                .isEqualTo(defaults);
        assertThat(resolver.resolve(defaults, null))
                .isEqualTo(defaults);
    }

    @Test
    void shouldApplyRamOverrideOnly() {
        assertThat(resolver.resolve(
                ResourceRequest.of(4096, 4, false),
                ResourceRequestOverrides.of(8192, null, null)
        )).isEqualTo(ResourceRequest.of(8192, 4, false));
    }

    @Test
    void shouldApplyCpuOverrideOnly() {
        assertThat(resolver.resolve(
                ResourceRequest.of(4096, 4, false),
                ResourceRequestOverrides.of(null, 8, null)
        )).isEqualTo(ResourceRequest.of(4096, 8, false));
    }

    @Test
    void shouldApplyGpuOverrideOnly() {
        assertThat(resolver.resolve(
                ResourceRequest.of(4096, 4, false),
                ResourceRequestOverrides.of(null, null, true)
        )).isEqualTo(ResourceRequest.of(4096, 4, true));
    }

    @Test
    void shouldApplyAllResourceOverridesAndAllowZeroValues() {
        assertThat(resolver.resolve(
                ResourceRequest.of(4096, 4, true),
                ResourceRequestOverrides.of(0, 0, false)
        )).isEqualTo(ResourceRequest.zero());
    }

    @Test
    void shouldRejectInvalidResourceRequests() {
        assertThatThrownBy(() -> ResourceRequest.of(-1, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceRequest.of(0, -1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceRequestOverrides.of(-1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(null, ResourceRequestOverrides.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
