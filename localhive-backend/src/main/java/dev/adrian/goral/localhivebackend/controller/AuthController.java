package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.User;
import dev.adrian.goral.localhivebackend.dto.AuthRequestDto;
import dev.adrian.goral.localhivebackend.dto.AuthResponseDto;
import dev.adrian.goral.localhivebackend.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody AuthRequestDto request) {
        log.info("Auth API: Attempting authentication for user: {}", request.getUsername());

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = Objects.requireNonNull((User) authentication.getPrincipal(),
                "Authenticated principal cannot be null");
        Collection<? extends GrantedAuthority> authorities = Objects.requireNonNull(
                user.getAuthorities(),
                "Authenticated user has no granted authorities"
        );

        String role = authorities.stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Authenticated user has no granted authorities"
                ));

        String token = jwtService.generateToken(user, user.getId().toString(), role);

        return ResponseEntity.ok(AuthResponseDto.builder()
                .token(token)
                .username(user.getUsername())
                .role(role)
                .userId(user.getId())
                .build());
    }
}