package com.lifeorganizer.error;

/** The proposal was already applied, rejected or expired, or belongs to another day's thread. */
public class ProposalNotAvailableException extends RuntimeException {

    public ProposalNotAvailableException(String message) {
        super(message);
    }
}
