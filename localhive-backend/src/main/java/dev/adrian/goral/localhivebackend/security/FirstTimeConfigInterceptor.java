package dev.adrian.goral.localhivebackend.security;

import dev.adrian.goral.localhivebackend.service.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class FirstTimeConfigInterceptor implements HandlerInterceptor {

    private final SetupService setupService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        String requestUri = request.getRequestURI();

        // Always allow CORS preflight requests (browsers send OPTIONS before actual requests)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        boolean isSetupEndpoint = requestUri.matches("^/api/setup(/.*)?$");
        boolean isSetupRequired;

        try {
            isSetupRequired = setupService.isSetupRequired();
        } catch (Exception e) {
            log.error("SECURITY/INFRA: Failed to verify system setup status from DB. Check PostgreSQL connection.", e);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal configuration check failed.");
        }

        // Scenario 1: System is not configured, but a request tries to access core API
        if (isSetupRequired && !isSetupEndpoint) {
            log.info("Blocked access to {}. System is locked in First-Time Config mode.", requestUri);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "System is locked. Please complete the First-Time Config wizard.");
        }

        // Scenario 2: System is already configured, but someone tries to run the setup again
        if (!isSetupRequired && isSetupEndpoint) {
            log.info("Blocked access to setup wizard at {}. System is already configured.", requestUri);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "System is already configured. Setup wizard is locked.");
        }

        // All good, let the request pass to the designated Controller
        return true;
    }
}