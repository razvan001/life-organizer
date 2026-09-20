package com.lifeorganizer.error;

/** The caller sent something invalid (bad JSON values, broken rules). Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
