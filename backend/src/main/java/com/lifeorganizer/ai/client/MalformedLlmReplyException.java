package com.lifeorganizer.ai.client;

/** The model's reply had no usable content, or content that wasn't the expected JSON shape - a content problem, not a connectivity one. */
public class MalformedLlmReplyException extends RuntimeException {

    public MalformedLlmReplyException(String message) {
        super(message);
    }
}
