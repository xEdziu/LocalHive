package dev.adrian.goral.localhivebackend.exception;

import dev.adrian.goral.localhivebackend.dto.ErrorResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Catches ResponseStatusException.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponseDto> handleResponseStatusException(ResponseStatusException ex) {
        ErrorResponseDto errorResponse = ErrorResponseDto.builder()
                .status("error")
                .message(ex.getReason()) // Gets the clean message without the HTTP status code prefix
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(errorResponse, ex.getStatusCode());
    }

    /**
     * Catches validation errors from @Valid annotations in our Controllers.
     * Extracts exactly which fields failed and why.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        ErrorResponseDto errorResponse = ErrorResponseDto.builder()
                .status("error")
                .message("Validation failed for the submitted request.")
                .fieldErrors(errors)
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Catches database unique constraint violations globally (e.g. duplicate Hostname or Username).
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleDataIntegrityViolationException(org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Database constraint violation detected: {}", ex.getMostSpecificCause().getMessage());

        ErrorResponseDto errorResponse = ErrorResponseDto.builder()
                .status("error")
                .message("A resource with this unique identifier already exists.")
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponseDto> handleDuplicateResourceException(DuplicateResourceException ex) {
        ErrorResponseDto errorResponse = ErrorResponseDto.builder()
                .status("error")
                .message(ex.getField() + " already exists.")
                .fieldErrors(Map.of(ex.getField(), "This value is already taken."))
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    /**
     * Fallback for any other unexpected RuntimeExceptions (prevents leaking Java stack traces to the frontend).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleAllOtherExceptions(Exception ex) {
        log.error("Unhandled exception caught globally: ", ex);

        ErrorResponseDto errorResponse = ErrorResponseDto.builder()
                .status("error")
                .message("An unexpected internal server error occurred.")
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}