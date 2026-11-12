package com.onfinapp.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The global exception handler: every exception that escapes a controller ends here, and the response is
 * chosen by its type:
 * <ul>
 *   <li>{@link ApiProblemException}: the status and message it carries;</li>
 *   <li>an invalid request (bad parameter, unknown route, wrong method): the matching 4xx;</li>
 *   <li>the database being unreachable, or a timeout: 503;</li>
 *   <li>anything else: 500 with a generic message, so internals never leak to the client.</li>
 * </ul>
 * Every error body has the shape the contract defines: type "about:blank", the status' reason phrase as
 * title, a detail sentence and the request path as instance. The .NET API produces the same responses.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    public static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";
    public static final String UNAVAILABLE_DETAIL = "The service is temporarily unavailable. Please try again.";
    public static final String INVALID_REQUEST_DETAIL = "The request is not valid.";

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ProblemDetail handleApiProblem(ApiProblemException e) {
        if (e.status().is5xxServerError()) {
            log.warn("Request refused with {}: {}", e.status().value(), e.getMessage());
        } else {
            log.info("Request refused with {}: {}", e.status().value(), e.getMessage());
        }
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }

    /** The database cannot be reached, a connection cannot be obtained, or a statement timed out. */
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        TransientDataAccessException.class,
        CannotCreateTransactionException.class,
    })
    ProblemDetail handleUnavailable(Exception e) {
        log.error("Request failed with 503: {}", e.getClass().getSimpleName(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_DETAIL);
    }

    /** Spring Security's own exceptions are answered by the security filter chain, not here. */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrowSecurityException(RuntimeException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Request failed with 500: {}", e.getClass().getSimpleName(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_DETAIL);
    }

    /** A constraint on a request parameter failed: report the first message. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse(INVALID_REQUEST_DETAIL);
        return problem(ex, detail, headers, status, request);
    }

    /** A parameter could not be converted, e.g. limit=abc. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ex, INVALID_REQUEST_DETAIL, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ex, "Not Found", headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ex, "Method Not Allowed", headers, status, request);
    }

    private ResponseEntity<Object> problem(
            Exception ex, String detail, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.info("Request refused with {}: {}", status.value(), detail);
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }
}
