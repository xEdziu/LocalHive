package dev.adrian.goral.localhivebackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF (Cross-Site Request Forgery) as it's not needed for stateless REST APIs
                .csrf(AbstractHttpConfigurer::disable)

                // Disable default HTML login form
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // Ensure the application is strictly stateless (no JSESSIONID cookies)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 4. Configure endpoint access rules
                .authorizeHttpRequests(auth -> auth
                        // Allow anyone to access the setup wizard endpoints
                        .requestMatchers("/api/setup/**").permitAll()
                        // Allow system endpoints (Spring Boot Actuator, errors)
                        .requestMatchers("/api/health", "/error").permitAll()
                        // Block everything else (requires authentication)
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}