package com.lifeorganizer.error;

/** A plan event referred to a block id that does not exist. */
public class UnknownBlockException extends RuntimeException {

    public UnknownBlockException(String message) {
        super(message);
    }
}
