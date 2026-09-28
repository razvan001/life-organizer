package com.lifeorganizer.memory.notes.dto;

public record NoteDto(long id, String title, String body, String createdAt, String updatedAt) {
}
