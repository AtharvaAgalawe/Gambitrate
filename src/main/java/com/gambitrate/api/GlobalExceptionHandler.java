package com.gambitrate.api;

import com.gambitrate.api.dto.ApiError;
import com.gambitrate.ingest.ChessComRateLimitException;
import com.gambitrate.ingest.ChessComUpstreamException;
import com.gambitrate.ingest.PgnParseException;
import com.gambitrate.ingest.PlayerNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PlayerNotFoundException.class)
    ResponseEntity<ApiError> handlePlayerNotFound(PlayerNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ChessComRateLimitException.class)
    ResponseEntity<ApiError> handleRateLimit(ChessComRateLimitException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request));
    }

    @ExceptionHandler(ChessComUpstreamException.class)
    ResponseEntity<ApiError> handleUpstream(ChessComUpstreamException exception, HttpServletRequest request) {
        log.warn("upstream_failure path={} message={}", request.getRequestURI(), exception.getMessage());
        return error(HttpStatus.BAD_GATEWAY, exception.getMessage(), request);
    }

    @ExceptionHandler(PgnParseException.class)
    ResponseEntity<ApiError> handlePgnParse(PgnParseException exception, HttpServletRequest request) {
        log.error("pgn_parse_failure path={}", request.getRequestURI(), exception);
        return error(HttpStatus.BAD_GATEWAY, "Chess.com returned a game that could not be parsed.", request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        String message = exception.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message.isBlank() ? "Invalid request." : message, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request parameter.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleBodyValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message.isBlank() ? "Invalid request." : message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unexpected_error path={}", request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status, message, request));
    }

    private static ApiError body(HttpStatus status, String message, HttpServletRequest request) {
        return new ApiError(
                status.value(),
                status.getReasonPhrase(),
                message,
                Instant.now(),
                request.getRequestURI()
        );
    }
}
