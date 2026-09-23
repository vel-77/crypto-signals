package com.cryptosignals.api;

import com.cryptosignals.api.dto.Dtos;
import com.cryptosignals.exchange.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns failures into predictable JSON instead of leaking stack traces to clients. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Dtos.ErrorDto> badRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(Dtos.ErrorDto.of("bad_request", ex.getMessage()));
    }

    @ExceptionHandler(ExchangeException.class)
    public ResponseEntity<Dtos.ErrorDto> exchangeFailure(ExchangeException ex) {
        log.warn("Exchange call failed: {}", ex.getMessage());
        // The exchange being unavailable is an upstream problem, not a client error.
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Dtos.ErrorDto.of("exchange_unavailable", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorDto> unexpected(Exception ex) {
        log.error("Unhandled API error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Dtos.ErrorDto.of("internal_error", "An unexpected error occurred"));
    }
}
