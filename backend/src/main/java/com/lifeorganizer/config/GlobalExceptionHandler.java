package com.lifeorganizer.config;

import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.AssistantUnavailableException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns the app's own exceptions into RFC 7807 problem responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the standard MVC exceptions (405, 415, 404 ...) keep
 * their proper status as problem+json instead of falling into the catch-all below. Spring Boot's own
 * problem-details advice backs off when a {@code ResponseEntityExceptionHandler} bean exists, so the
 * "Malformed request" wording below is the one clients see.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SetupRequiredException.class)
    public ProblemDetail setupRequired(SetupRequiredException e) {
        return problem(404, "Setup required", e.getMessage());
    }

    @ExceptionHandler(UnknownBlockException.class)
    public ProblemDetail unknownBlock(UnknownBlockException e) {
        return problem(422, "Unknown block", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail invalidRequest(InvalidRequestException e) {
        return problem(400, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(AssistantUnavailableException.class)
    public ProblemDetail assistantUnavailable(AssistantUnavailableException e) {
        log.warn("Assistant unavailable: {}", e.getMessage(), e.getCause());
        return problem(502, "Assistant unavailable", e.getMessage());
    }

    @ExceptionHandler(AiNotConfiguredException.class)
    public ProblemDetail aiNotConfigured(AiNotConfiguredException e) {
        log.debug("AI endpoint called with no key configured: {}", e.getMessage());
        return problem(503, "AI features are not configured", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        log.warn("Malformed request body: {}", e.getMessage());
        ProblemDetail body = problem(400, "Malformed request", "The request body is missing or is not valid JSON");
        return handleExceptionInternal(e, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Last resort: log the cause server-side, tell the client nothing about it. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(500, "Unexpected error", "Something went wrong");
    }

    private static ProblemDetail problem(int status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        return problem;
    }
}
