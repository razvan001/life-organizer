package com.lifeorganizer.memory.notes;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.NoteNotFoundException;
import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Note CRUD. Every write re-indexes the note in the same transaction, so a failed index rolls back the save. */
@Service
public class NoteService {

    static final int MAX_TITLE = 50;
    static final int MAX_BODY = 500;

    private final NoteRepository notes;
    private final MemoryIndexer indexer;
    private final Clock clock;

    public NoteService(NoteRepository notes, MemoryIndexer indexer, Clock clock) {
        this.notes = notes;
        this.indexer = indexer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NoteDto> list() {
        return notes.findAllByOrderByUpdatedAtDescIdDesc().stream().map(NoteService::toDto).toList();
    }

    @Transactional
    public NoteDto create(NoteRequest request) {
        String title = validTitle(request.title());
        String body = validBody(request.body());
        NoteEntity saved = notes.save(new NoteEntity(title, body, Instant.now(clock)));
        indexer.indexNote(saved.getId(), title, body);
        return toDto(saved);
    }

    @Transactional
    public NoteDto update(long id, NoteRequest request) {
        NoteEntity note = notes.findById(id).orElseThrow(() -> new NoteNotFoundException(id));
        String title = validTitle(request.title());
        String body = validBody(request.body());
        note.edit(title, body, Instant.now(clock));
        indexer.indexNote(id, title, body);
        return toDto(note);
    }

    @Transactional
    public void delete(long id) {
        if (!notes.existsById(id)) {
            throw new NoteNotFoundException(id);
        }
        notes.deleteById(id);
        indexer.removeNote(id);
    }

    private static String validTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE) {
            throw new InvalidRequestException("title must be at most " + MAX_TITLE + " characters");
        }
        return trimmed;
    }

    private static String validBody(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidRequestException("body is required");
        }
        if (body.length() > MAX_BODY) {
            throw new InvalidRequestException("body must be at most " + MAX_BODY + " characters");
        }
        return body;
    }

    private static NoteDto toDto(NoteEntity note) {
        return new NoteDto(note.getId(), note.getTitle(), note.getBody(),
            note.getCreatedAt().toString(), note.getUpdatedAt().toString());
    }
}
