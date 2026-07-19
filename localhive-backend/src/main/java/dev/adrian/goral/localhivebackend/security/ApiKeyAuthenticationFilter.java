package dev.adrian.goral.localhivebackend.security;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
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
    private static final String WORKER_AUTHENTICATION_FAILED_MESSAGE = "Worker authentication failed.";

    // Public endpoints that don't require API Key authentication
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/setup/status",
            "/api/setup",
            "/api/auth/login",
            "/api/workers/register"
    );

    private final WorkerRepository workerRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApiErrorResponseWriter errorResponseWriter;

    private static final Pattern WORKER_API_PATH_PATTERN = Pattern.compile(
            "^/api/workers/([^/]+)/(heartbeat|allocation|spec|assigned-executions/claim-next|executions/[^/]+/(running|succeeded|failed|lease/renew|artifacts/output|artifacts/[^/]+/download))$"
    );

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        Matcher matcher = WORKER_API_PATH_PATTERN.matcher(requestUri);

        // Allow public paths, admin endpoints (protected by JWT), and non-worker-API requests.
        if (PUBLIC_PATHS.contains(requestUri) || requestUri.startsWith("/api/admin/") || !matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            writeUnauthorized(response);
            log.debug("Rejected request to {}: missing API key header", requestUri);
            return;
        }

        try {
            UUID workerId = UUID.fromString(matcher.group(1));
            var workerOpt = workerRepository.findById(workerId);

            if (workerOpt.isPresent()
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && isApprovedWorkerWithMatchingApiKey(workerOpt.get(), apiKey)) {
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

        writeUnauthorized(response);
        log.debug("Rejected request to {}: invalid API key", requestUri);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, WORKER_AUTHENTICATION_FAILED_MESSAGE);
    }

    private boolean isApprovedWorkerWithMatchingApiKey(Worker worker, String apiKey) {
        return worker.getApprovalStatus() == WorkerApprovalStatus.APPROVED
                && worker.getApiKeyHash() != null
                && passwordEncoder.matches(apiKey, worker.getApiKeyHash());
    }
}
