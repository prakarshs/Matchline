package dev.matchline.spring.api;

import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.RejectionCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One transport error envelope. Invalid DTOs must not be admitted to the writer,
 * whereas a duplicate ID can only be rejected by the sequenced business decision.
 */
// Keep this advice global: unsupported Content-Type is rejected during handler
// selection, so Spring has no controller class to match a scoped advice against.
@RestControllerAdvice
public final class SpringApiErrorAdvice {
    @ExceptionHandler(SpringApiException.class)
    ResponseEntity<Map<String, Object>> expected(SpringApiException e) {
        return error(e.status(), e.code(), e.getMessage(), e.sequence());
    }

    @ExceptionHandler(OrderValidationException.class)
    ResponseEntity<Map<String, Object>> domain(OrderValidationException e) {
        int status = switch (e.code()) {
            case INGRESS_OVERLOADED -> 429;
            case INGRESS_CLOSED -> 503;
            default -> 422;
        };
        return error(status, e.code().name(), e.getMessage(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> invalidJson(HttpMessageNotReadableException e) {
        return error(400, "INVALID_JSON", "request must be a valid flat JSON object", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return error(405, "METHOD_NOT_ALLOWED", "method not supported for this endpoint", null);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message, Long sequence) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("message", message);
        if (sequence != null) details.put("sequence", sequence);
        return ResponseEntity.status(HttpStatusCode.valueOf(status)).body(Map.of("error", details));
    }
}
