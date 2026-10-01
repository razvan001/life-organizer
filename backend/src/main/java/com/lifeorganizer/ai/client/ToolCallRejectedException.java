package com.lifeorganizer.ai.client;

/** Groq refused the model's tool call itself (unknown tool, or arguments that break the declared schema); the model can retry. */
public class ToolCallRejectedException extends RuntimeException {

    public ToolCallRejectedException(String message) {
        super(message);
    }
}
