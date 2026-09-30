package com.lifeorganizer.memory.search;

import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySearchTest {

    private final MemoryChunkStore store = mock(MemoryChunkStore.class);
    private final MemorySearch search = new MemorySearch(new HashingTextEmbedder(), store);

    private static MemoryHit hit(SourceType type, String id, String text, double score) {
        return new MemoryHit(type, id, type == SourceType.DAY ? LocalDate.of(2026, 9, 24) : null, "T" + id, text, score);
    }

    @Test
    void keepsOnlyTheBestChunkOfEachSource() {
        when(store.search(any(), eq(20), isNull())).thenReturn(List.of(
            hit(SourceType.NOTE, "1", "best chunk of note 1", 0.9),
            hit(SourceType.NOTE, "1", "second chunk of note 1", 0.8),
            hit(SourceType.NOTE, "2", "note 2", 0.7),
            hit(SourceType.DAY, "2026-09-24", "a day", 0.6)));

        List<MemoryHit> hits = search.search("anything", 5, null);

        assertThat(hits).extracting(MemoryHit::text)
            .containsExactly("best chunk of note 1", "note 2", "a day");
    }

    @Test
    void returnsAtMostKSources() {
        when(store.search(any(), eq(8), eq(SourceType.NOTE))).thenReturn(List.of(
            hit(SourceType.NOTE, "1", "a", 0.9), hit(SourceType.NOTE, "2", "b", 0.8),
            hit(SourceType.NOTE, "3", "c", 0.7)));

        assertThat(search.search("anything", 2, SourceType.NOTE)).hasSize(2);
    }
}
