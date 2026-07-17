package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequestOverrides;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ResourceRequestResolver {

    public ResourceRequest resolve(ResourceRequest defaultResourceRequest, ResourceRequestOverrides resourceOverrides) {
        ResourceRequest defaults = Objects.requireNonNull(
                defaultResourceRequest,
                "defaultResourceRequest must not be null."
        );
        ResourceRequestOverrides overrides = resourceOverrides == null
                ? ResourceRequestOverrides.empty()
                : resourceOverrides;

        return ResourceRequest.of(
                overrides.getRequiredRamMb() == null
                        ? defaults.getRequiredRamMb()
                        : overrides.getRequiredRamMb(),
                overrides.getRequiredCpuCores() == null
                        ? defaults.getRequiredCpuCores()
                        : overrides.getRequiredCpuCores(),
                overrides.getGpuRequired() == null
                        ? defaults.isGpuRequired()
                        : overrides.getGpuRequired()
        );
    }
}
