package dev.adrian.goral.localhivebackend.config;

import dev.adrian.goral.localhivebackend.security.ApiKeyAuthenticationFilter;
import dev.adrian.goral.localhivebackend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final ApiKeyAuthenticationFilter apiKeyAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                // Disable CSRF as it's not needed for stateless REST APIs
                .csrf(AbstractHttpConfigurer::disable)

                // Disable default HTML login forms
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // Strictly stateless architecture
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Configure provider and wire JWT filter into the pipeline
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // Endpoint access rules
                .authorizeHttpRequests(auth -> auth
                        // Allow setup wizard endpoints to be public
                        .requestMatchers("/api/setup/**").permitAll()

                        // Allow authentication endpoint to be public
                        .requestMatchers("/api/auth/**").permitAll()

                        // Allow raw agent interaction endpoints (Security tokens handled at service level)
                        .requestMatchers("/api/workers/register").permitAll()


                        // Allow system infrastructure endpoints
                        .requestMatchers("/api/health", "/error").permitAll()

                        .requestMatchers("/api/workers/**").authenticated()

                        // Core dashboard administration explicitly locked under ADMIN role
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // Block everything else
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}