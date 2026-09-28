package com.lifeorganizer.error;

public class NoteNotFoundException extends RuntimeException {

    public NoteNotFoundException(long id) {
        super("No note with id " + id);
    }
}
