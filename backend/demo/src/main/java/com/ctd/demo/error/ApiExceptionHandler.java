package com.ctd.demo.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Converts controller, validation, and authentication failures to API responses. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 2026-09-23: Safe change-password feedback; do not log credential request/validation values. */
    @ExceptionHandler(com.ctd.demo.auth.PasswordChangeRejected.class)
    ResponseEntity<Map<String, Object>> passwordRejected(com.ctd.demo.auth.PasswordChangeRejected exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PASSWORD_CHANGE",
                "message", "Check the password fields.", "fieldErrors", Map.of(exception.field(), exception.getMessage())));
    }

    /** Maps explicit rejections to stable public codes without exposing arbitrary reasons. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<Map<String, String>> statusError(org.springframework.web.server.ResponseStatusException exception) {
        if (exception.getStatusCode().value() == 400)
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
        if (exception.getStatusCode().value() == 404)
            return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The requested resource was not found.");
        if (exception.getStatusCode().value() == 409)
            return error(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "The request conflicts with existing data.");
        if (exception.getStatusCode().value() == 429)
            return error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Please try again later.");
        if (exception.getStatusCode().value() == 503)
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "The service is temporarily unavailable.");
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "code", "REQUEST_REJECTED", "message", "The request was rejected."));
    }

    /** Reports an invalid path or query parameter as a bad request. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> invalidParameter() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request parameter.");
    }

    /** Returns a general unauthorized response for authentication failures. */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, String>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect.");
    }

    /** Maps bean-validation failures to user-facing field errors. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidRequest(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> {
            String field = error.getField();
            String message = switch (field) {
                case "username" -> "NotBlank".equals(error.getCode())
                        ? "Enter your username."
                        : "Username must be 64 characters or fewer.";
                case "password" -> "NotBlank".equals(error.getCode())
                        ? "Enter your password."
                        : "Password must be 72 characters or fewer.";
                default -> "Enter a valid value.";
            };
            fieldErrors.putIfAbsent(field, message);
        });
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_REQUEST",
                "message", "Provide valid request fields.",
                "fieldErrors", fieldErrors));
    }

    /** Returns a bad request response for malformed JSON bodies. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformedRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Provide valid request fields.");
    }

    /** Logs only the exception type and hides internal details from clients. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        // Structured logging includes the requestId from MDC; request DTOs redact credentials.
        log.error("Request failed with exception type {}", exception.getClass().getName(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unable to process the request.");
    }

    /** Reports unsupported paths, methods, and content types. */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class, NoResourceFoundException.class})
    ResponseEntity<Map<String, String>> unsupportedRequest(Exception exception) {
        var status = ((ErrorResponse) exception).getStatusCode();
        return ResponseEntity.status(status).body(Map.of("code", "UNSUPPORTED_REQUEST",
                "message", "The requested resource, method, or content type is not supported."));
    }

    /** Builds the common status, code, and message response body. */
    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
