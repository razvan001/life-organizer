package com.lifeorganizer.error;

/** The LLM call failed for a connectivity reason (network, auth, rate limit, timeout). */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
