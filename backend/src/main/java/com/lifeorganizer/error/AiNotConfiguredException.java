package com.lifeorganizer.error;

/** No LLM client is configured (no GROQ_API_KEY), but an AI endpoint was called anyway. */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
