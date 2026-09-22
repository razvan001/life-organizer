package com.lifeorganizer.ai.client;

/** Claude's structured reply had no usable text content - a content problem, not a connectivity one. */
public class MalformedClaudeReplyException extends RuntimeException {

    public MalformedClaudeReplyException(String message) {
        super(message);
    }
}
