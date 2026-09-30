package com.lifeorganizer.error;

public class ChatMessageNotFoundException extends RuntimeException {

    public ChatMessageNotFoundException(long id) {
        super("No chat message with id " + id);
    }
}
