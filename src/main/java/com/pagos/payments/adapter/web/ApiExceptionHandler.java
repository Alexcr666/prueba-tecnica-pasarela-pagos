package com.pagos.payments.adapter.web;

import com.pagos.payments.application.ApiException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
    }

    /** Base de datos no disponible: 503 para que el cliente o el proveedor reintenten. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            QueryTimeoutException.class})
    ResponseEntity<Map<String, String>> unavailable(Exception e) {
        log.error("Base de datos no disponible: {}", e.getMessage());
        return ResponseEntity.status(503).body(Map.of("code", "UNAVAILABLE", "message", "Intenta de nuevo"));
    }
}
