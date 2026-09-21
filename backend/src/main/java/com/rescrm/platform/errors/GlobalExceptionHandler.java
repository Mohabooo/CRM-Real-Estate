package com.rescrm.platform.errors;

import com.rescrm.platform.money.CurrencyMismatchException;
import com.rescrm.platform.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates exceptions into the single API error envelope.
 *
 * <p>Two rules govern this class. First, every handler produces {@link ApiErrorResponse} —
 * there is no second error shape anywhere in the API. Second, unexpected failures never leak
 * their message to the caller: they are logged with the correlation id and returned as a
 * generic INTERNAL_ERROR, because a stack trace in a response body is both a security problem
 * and useless to a user.
 *
 * <p>Note that domain exceptions carry their own {@link ErrorCode} and detail map by extending
 * {@link ApiException}, so this class does not need a handler per domain — including for
 * financial errors such as a violated schedule invariant, whose figures travel with the
 * exception. That is why {@code platform} has no dependency on any domain package.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Every {@link ApiException}, including domain subclasses.
     *
     * <p>Logged at WARN for client-attributable statuses and ERROR for 5xx: a 422 is the system
     * working correctly and should not wake anyone, while a 500 is a defect.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        if (exception.code().httpStatus() >= 500) {
            log.error("API error [{}]: {}", exception.code(), exception.getMessage(), exception);
        } else {
            log.warn("API error [{}]: {} {}", exception.code(), exception.getMessage(),
                    exception.details().isEmpty() ? "" : exception.details());
        }
        return build(exception.code(), exception.getMessage(), exception.details());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleCurrencyMismatch(CurrencyMismatchException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("left", exception.left().isoCode());
        details.put("right", exception.right().isoCode());
        return build(ErrorCode.CURRENCY_MISMATCH, exception.getMessage(), details);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fields.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        log.debug("Unreadable request body", exception);
        return build(ErrorCode.VALIDATION_FAILED, "Request body could not be parsed", Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return build(ErrorCode.VALIDATION_FAILED, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException exception) {
        return build(ErrorCode.NOT_FOUND, "No handler for " + exception.getRequestURL(), Map.of());
    }

    /**
     * Spring Boot 3.2 and later hand an unmatched path to the static resource handler, which
     * raises this instead of {@link NoHandlerFoundException}. Without a handler for it the
     * catch-all below answered every unknown URL with 500, which is both wrong and a quiet
     * invitation to probe the API: a 500 says "something broke", a 404 says "no such thing".
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception) {
        return build(ErrorCode.NOT_FOUND, "No handler for /" + exception.getResourcePath(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled exception [correlationId={}]", CorrelationId.current(), exception);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", Map.of());
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode code, String message,
                                                   Map<String, Object> details) {
        ApiErrorResponse body = ApiErrorResponse.of(code, message, details, CorrelationId.current());
        return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus())).body(body);
    }
}
