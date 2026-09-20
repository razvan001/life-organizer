package com.lifeorganizer.error;

/** No goals/constraints have been saved yet, so no plan can be generated. */
public class SetupRequiredException extends RuntimeException {

    public SetupRequiredException(String message) {
        super(message);
    }
}
