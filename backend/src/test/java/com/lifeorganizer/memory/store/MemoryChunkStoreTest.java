package com.lifeorganizer.memory.store;

import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(MemoryChunkStore.class)
class MemoryChunkStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    @Autowired
    MemoryChunkStore store;

    private NewChunk chunk(String text) {
        return new NewChunk(text, embedder.embed(text));
    }

    @Test
    void searchOnAnEmptyStoreReturnsNothing() {
        assertThat(store.search(embedder.embed("anything"), 5, null)).isEmpty();
    }

    @Test
    void closestChunkComesFirstWithAScoreNearOne() {
        store.replace(SourceType.NOTE, "1", null, "Gym", "h1", List.of(chunk("gym makes me tired")));
        store.replace(SourceType.NOTE, "2", null, "Car", "h2", List.of(chunk("car invoice due friday")));

        List<MemoryHit> hits = store.search(embedder.embed("gym makes me tired"), 5, null);

        assertThat(hits).extracting(MemoryHit::sourceId).containsExactly("1", "2");
        assertThat(hits.get(0).score()).isGreaterThan(0.99);
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void kLimitsTheNumberOfHits() {
        for (int i = 0; i < 5; i++) {
            store.replace(SourceType.NOTE, "n" + i, null, "T", "h", List.of(chunk("word" + i)));
        }
        assertThat(store.search(embedder.embed("word1"), 3, null)).hasSize(3);
    }

    @Test
    void sourceFilterKeepsOnlyThatType() {
        LocalDate day = LocalDate.of(2026, 9, 24);
        store.replace(SourceType.NOTE, "1", null, "Note", "h", List.of(chunk("gym postponed")));
        store.replace(SourceType.DAY, day.toString(), day, "Thu 2026-09-24", "h", List.of(chunk("gym postponed")));

        List<MemoryHit> hits = store.search(embedder.embed("gym postponed"), 5, SourceType.DAY);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.sourceType()).isEqualTo(SourceType.DAY);
            assertThat(hit.sourceDate()).isEqualTo(day);
        });
    }

    @Test
    void replaceSwapsAllChunksOfThatSourceOnly() {
        store.replace(SourceType.NOTE, "1", null, "A", "old", List.of(chunk("alpha"), chunk("beta")));
        store.replace(SourceType.NOTE, "2", null, "B", "keep", List.of(chunk("gamma")));

        store.replace(SourceType.NOTE, "1", null, "A", "new", List.of(chunk("delta")));

        assertThat(store.search(embedder.embed("alpha beta delta gamma"), 10, null))
            .extracting(MemoryHit::text).containsExactlyInAnyOrder("delta", "gamma");
        assertThat(store.contentHash(SourceType.NOTE, "1")).contains("new");
    }

    @Test
    void deleteRemovesTheSource() {
        store.replace(SourceType.NOTE, "1", null, "A", "h", List.of(chunk("alpha")));

        store.delete(SourceType.NOTE, "1");

        assertThat(store.search(embedder.embed("alpha"), 5, null)).isEmpty();
        assertThat(store.contentHash(SourceType.NOTE, "1")).isEmpty();
    }

    @Test
    void quotesAndNonAsciiTextRoundTripUnchanged() {
        String text = "O'Brien's café \"notes\" -- 100% ✓";
        store.replace(SourceType.NOTE, "1", null, "Title with 'quotes'", "h", List.of(chunk(text)));

        MemoryHit hit = store.search(embedder.embed(text), 1, null).get(0);

        assertThat(hit.text()).isEqualTo(text);
        assertThat(hit.title()).isEqualTo("Title with 'quotes'");
    }
}
