package com.example.testinglearning.order;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.List;

/**
 * Translates service-layer exceptions into RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} makes Spring MVC's own failures (malformed
 * JSON, a path variable that isn't a number, a missing query parameter, unsupported media types)
 * use the same {@code application/problem+json} body shape, so clients get one error contract
 * instead of two.
 */
@RestControllerAdvice
public class OrderExceptionHandler extends ResponseEntityExceptionHandler {

    /** One entry of the {@code errors} array in a 400 validation response. */
    record FieldViolation(String field, String message) {
    }

    private static final Comparator<FieldViolation> BY_FIELD_THEN_MESSAGE =
            Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);

    /**
     * A {@code @Valid @RequestBody} failed. Spring's default 400 body only says "Invalid request
     * content.", so this adds an {@code errors} array saying <em>which</em> field broke
     * <em>which</em> rule. It's sorted, so the order is stable for clients and for tests
     * (Hibernate Validator reports violations in no guaranteed order).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .sorted(BY_FIELD_THEN_MESSAGE)
                .toList();
        return handleExceptionInternal(ex, validationProblem(ex.getBody(), errors), headers, status, request);
    }

    /**
     * A constraint directly on a controller parameter (e.g. {@code @Email} on a
     * {@code @RequestParam}) failed. Spring 6.1+ runs that "method validation" itself and reports it
     * through this different exception. Same body shape as above, with the parameter name as
     * {@code field}.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(MessageSourceResolvable::getDefaultMessage)
                        .map(message -> new FieldViolation(result.getMethodParameter().getParameterName(), message)))
                .sorted(BY_FIELD_THEN_MESSAGE)
                .toList();
        return handleExceptionInternal(ex, validationProblem(ex.getBody(), errors), headers, status, request);
    }

    private static ProblemDetail validationProblem(ProblemDetail problem, List<FieldViolation> errors) {
        problem.setTitle("Validation failed");
        problem.setDetail("The request has " + errors.size() + " invalid field(s)");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(OrderNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Order not found");
        return problem;
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    ProblemDetail handleInvalidOrderState(InvalidOrderStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Invalid order state");
        return problem;
    }

    @ExceptionHandler(CancellationWindowExpiredException.class)
    ProblemDetail handleCancellationWindowExpired(CancellationWindowExpiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Cancellation window expired");
        return problem;
    }

    // Two requests changed the same order at the same time, and this one lost (see @Version on
    // Order). 409 rather than 500: nothing is broken; the client should reload and decide again.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(OptimisticLockingFailureException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The order was changed by another request at the same time; reload it and try again");
        problem.setTitle("Concurrent modification");
        return problem;
    }

    @ExceptionHandler(DuplicateOrderNumberException.class)
    ProblemDetail handleDuplicateOrderNumber(DuplicateOrderNumberException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Duplicate order number");
        return problem;
    }

    @ExceptionHandler(ShipmentRejectedException.class)
    ProblemDetail handleShipmentRejected(ShipmentRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Shipment rejected by carrier");
        return problem;
    }

    // 503 rather than 500: our service is fine, but a dependency it needs isn't right now, so a
    // client may try again later.
    @ExceptionHandler(ShippingUnavailableException.class)
    ProblemDetail handleShippingUnavailable(ShippingUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Shipping carrier unavailable");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
