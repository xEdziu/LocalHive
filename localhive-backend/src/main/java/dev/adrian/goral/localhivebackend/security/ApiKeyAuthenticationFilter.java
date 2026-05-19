package dev.adrian.goral.localhivebackend.security;

import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String MISSING_API_KEY_MESSAGE = "Missing X-API-KEY header";
    private static final String INVALID_API_KEY_MESSAGE = "Invalid API Key";

    // Public endpoints that don't require API Key authentication
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/setup/status",
            "/api/setup",
            "/api/auth/login",
            "/api/workers/register"
    );

    private final WorkerRepository workerRepository;
    private final PasswordEncoder passwordEncoder;

    private static final Pattern WORKER_ID_PATTERN = Pattern.compile("/api/workers/([^/]+)/.*");

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // Allow public paths and admin endpoints (they're protected by JWT)
        if (PUBLIC_PATHS.contains(requestUri) || requestUri.startsWith("/api/admin/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(MISSING_API_KEY_MESSAGE);
            log.debug("Rejected request to {}: missing API key header", requestUri);
            return;
        }

        Matcher matcher = WORKER_ID_PATTERN.matcher(requestUri);
        if (matcher.find()) {
            try {
                UUID workerId = UUID.fromString(matcher.group(1));
                var workerOpt = workerRepository.findById(workerId);

                if (workerOpt.isPresent()
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        && passwordEncoder.matches(apiKey, workerOpt.get().getApiKeyHash())) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(workerId, null, List.of())
                    );
                    filterChain.doFilter(request, response);
                    log.debug("Worker {} authenticated successfully for {}", workerId, requestUri);
                    return;
                }
            } catch (IllegalArgumentException e) {
                log.debug("Rejected request to {}: invalid worker ID in path", requestUri);
            }
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write(INVALID_API_KEY_MESSAGE);
        log.debug("Rejected request to {}: invalid API key", requestUri);
    }
}