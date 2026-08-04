package dev.adrian.goral.localhivebackend.config;

import dev.adrian.goral.localhivebackend.websocket.AdminResearchWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AdminResearchWebSocketConfig implements WebSocketConfigurer {

    private final AdminResearchWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/admin/research/ws")
                .addInterceptors(adminOnlyHandshakeInterceptor());
    }

    @Bean
    public HandshakeInterceptor adminOnlyHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request,
                                           ServerHttpResponse response,
                                           WebSocketHandler wsHandler,
                                           Map<String, Object> attributes) {
                if (request.getPrincipal() instanceof AbstractAuthenticationToken authentication
                        && authentication.isAuthenticated()
                        && hasAdminRole(authentication)) {
                    return true;
                }

                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request,
                                       ServerHttpResponse response,
                                       WebSocketHandler wsHandler,
                                       Exception exception) {
                // No handshake state is retained.
            }
        };
    }

    private static boolean hasAdminRole(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
