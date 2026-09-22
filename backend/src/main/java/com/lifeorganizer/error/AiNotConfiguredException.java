package com.lifeorganizer.error;

/** No Claude client is configured (no ANTHROPIC_API_KEY), but an AI endpoint was called anyway. */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
