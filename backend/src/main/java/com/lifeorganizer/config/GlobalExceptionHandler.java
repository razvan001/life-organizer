package com.lifeorganizer.config;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the app's own exceptions into RFC 7807 problem responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return problem(400, "Malformed request", "The request body is missing or is not valid JSON");
    }

    private static ProblemDetail problem(int status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        return problem;
    }
}
