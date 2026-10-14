package dev.genericpayments.platform.shared.adapter.in.web;

import dev.genericpayments.platform.admin.application.service.InvalidCredentialsException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(dev.genericpayments.platform.shared.application.RateLimitExceededException.class)
    ResponseEntity<Object> rateLimit(dev.genericpayments.platform.shared.application.RateLimitExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(429).header("Retry-After", Integer.toString(exception.retryAfterSeconds()))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ApiProblem.of(429, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded", request));
    }
    @ExceptionHandler(dev.genericpayments.platform.shared.application.PaymentApplicationException.class)
    ResponseEntity<Object> paymentApplication(dev.genericpayments.platform.shared.application.PaymentApplicationException exception, HttpServletRequest request) {
        var body = new java.util.LinkedHashMap<String, Object>();
        var problem = ApiProblem.of(exception.status(), exception.code(), exception.title(), request);
        body.put("type", problem.type()); body.put("title", problem.title()); body.put("status", problem.status());
        body.put("code", problem.code()); body.put("request_id", problem.requestId()); body.putAll(exception.extensions());
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<Object> invalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
        return problem(401, "invalid_credentials", "Invalid credentials", request);
    }
    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<Object> invalidDomain(DomainValidationException exception, HttpServletRequest request) {
        return problem(400, "invalid_request", "Invalid request", request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        return problem(500, "internal_error", "Internal server error", request);
    }
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,org.springframework.dao.OptimisticLockingFailureException.class})
    ResponseEntity<Object> conflict(Exception exception, HttpServletRequest request) {
        return problem(409, "resource_conflict", "Resource conflict", request);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        if (exception instanceof org.springframework.web.bind.MethodArgumentNotValidException validation) {
            var errors = validation.getBindingResult().getFieldErrors().stream().map(error -> java.util.Map.of(
                "field", error.getField().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT),
                "code", "INVALID_VALUE")).distinct().toList();
            var problem = ApiProblem.of(status.value(), invalidCode(request), "Invalid request", ((ServletWebRequest) request).getRequest());
            var safeBody = new java.util.LinkedHashMap<String, Object>();
            safeBody.put("type", problem.type()); safeBody.put("title", problem.title()); safeBody.put("status", problem.status());
            safeBody.put("code", problem.code()); safeBody.put("request_id", problem.requestId()); safeBody.put("field_errors", errors);
            return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(safeBody);
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblem.of(status.value(), invalidCode(request),
                status.value() >= 500 ? "Internal server error" : "Invalid request",
                ((ServletWebRequest) request).getRequest()));
    }
    private static String invalidCode(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI().startsWith("/api/v1/admin/payments")
            ? "PAYMENT_REQUEST_INVALID" : "invalid_request";
    }
    private ResponseEntity<Object> problem(int status, String code, String title, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblem.of(status, code, title, request));
    }
}
