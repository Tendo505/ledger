package io.github.tendo505.ledger.api;

import io.github.tendo505.ledger.core.ApiException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 7807 problem ({@code application/problem+json}) with a
 * machine-readable {@code code}, e.g. {@code insufficient_funds}. Framework
 * errors (bad JSON, missing headers, failed validation) come from the
 * superclass in the same format.
 */
@RestControllerAdvice
public class Errors extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraint(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    /** The database refused the change (e.g. the no-overdraft constraint). The service checks first, so this is a backstop. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "constraint_violation", "the change would break a ledger invariant and was rolled back");
    }

    /** Validation failures list every bad field, so a client can fix them all at once. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "invalid_request", errors.size() + " field(s) are invalid");
        p.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(p);
    }

    /** Gives the framework's own problems (bad JSON, missing header, ...) a code too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers, HttpStatusCode status,
                                                             WebRequest request) {
        if (body instanceof ProblemDetail p && (p.getProperties() == null || !p.getProperties().containsKey("code"))) {
            p.setProperty("code", "invalid_request");
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://github.com/Tendo505/ledger#" + code.replace('_', '-')));
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        return p;
    }
}
