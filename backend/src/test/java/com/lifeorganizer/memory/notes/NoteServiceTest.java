package com.lifeorganizer.memory.notes;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.NoteNotFoundException;
import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({NoteService.class, MemoryIndexer.class, MemoryChunkStore.class, NoteServiceTest.Beans.class})
class NoteServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class Beans {
        @Bean
        TextEmbedder textEmbedder() {
            return new HashingTextEmbedder();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    NoteService notes;

    @Autowired
    MemoryChunkStore store;

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    private java.util.List<MemoryHit> search(String text) {
        return store.search(embedder.embed(text), 10, null);
    }

    @Test
    void createStoresTheNoteAndMakesItSearchable() {
        NoteDto created = notes.create(new NoteRequest("Gym", "after gym I can't focus on study"));

        assertThat(created.id()).isPositive();
        assertThat(created.createdAt()).isEqualTo("2026-09-28T10:00:00Z");
        assertThat(notes.list()).extracting(NoteDto::title).containsExactly("Gym");
        assertThat(search("focus on study")).extracting(MemoryHit::sourceId).containsExactly(Long.toString(created.id()));
    }

    @Test
    void updateReplacesTheSearchableText() {
        NoteDto created = notes.create(new NoteRequest("Gym", "alpha"));

        notes.update(created.id(), new NoteRequest("Gym", "omega"));

        assertThat(search("alpha omega")).extracting(MemoryHit::text).containsExactly("omega");
    }

    @Test
    void deleteRemovesTheNoteAndItsChunks() {
        NoteDto created = notes.create(new NoteRequest("Gym", "alpha"));

        notes.delete(created.id());

        assertThat(notes.list()).isEmpty();
        assertThat(search("alpha")).isEmpty();
    }

    @Test
    void anEmptyBodyIsAllowed() {
        NoteDto created = notes.create(new NoteRequest("Padel ideas", null));

        assertThat(created.body()).isEmpty();
        assertThat(search("padel ideas")).hasSize(1);
    }

    @Test
    void unknownIdsAreNotFound() {
        assertThatThrownBy(() -> notes.update(999, new NoteRequest("x", "y"))).isInstanceOf(NoteNotFoundException.class);
        assertThatThrownBy(() -> notes.delete(999)).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> notes.create(new NoteRequest(" ", "b")))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title is required");
        assertThatThrownBy(() -> notes.create(new NoteRequest("t".repeat(256), "b")))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title must be at most 255 characters");
        assertThatThrownBy(() -> notes.create(new NoteRequest("t", "b".repeat(20_001))))
            .isInstanceOf(InvalidRequestException.class).hasMessage("body must be at most 20000 characters");
    }
}
