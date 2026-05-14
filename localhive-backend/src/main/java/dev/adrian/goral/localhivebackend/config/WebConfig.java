package dev.adrian.goral.localhivebackend.config;

import dev.adrian.goral.localhivebackend.security.FirstTimeConfigInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final FirstTimeConfigInterceptor firstTimeConfigInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Register our security gatekeeper
        registry.addInterceptor(firstTimeConfigInterceptor)
                .addPathPatterns("/api/**")
                // Exclude endpoints that must always be accessible (e.g., Docker health checks)
                .excludePathPatterns("/api/health", "/api/actuator/**", "/error");
    }
}