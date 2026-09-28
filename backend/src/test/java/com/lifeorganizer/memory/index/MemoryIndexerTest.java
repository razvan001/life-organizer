package com.lifeorganizer.memory.index;

import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.NewChunk;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexerTest {

    private final MemoryChunkStore store = mock(MemoryChunkStore.class);
    private final MemoryIndexer indexer = new MemoryIndexer(new HashingTextEmbedder(), store);

    @SuppressWarnings("unchecked")
    private List<NewChunk> storedChunks(SourceType type, String sourceId) {
        ArgumentCaptor<List<NewChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replace(eq(type), eq(sourceId), any(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void aNoteIsStoredAsItsChunks() {
        indexer.indexNote(7, "Gym", "after gym\n\nI can't focus on study");

        List<NewChunk> chunks = storedChunks(SourceType.NOTE, "7");
        assertThat(chunks).extracting(NewChunk::text).containsExactly("after gym I can't focus on study");
        assertThat(chunks.get(0).embedding()).hasSize(384);
    }

    @Test
    void aNoteWithAnEmptyBodyIsStillFindableByItsTitle() {
        indexer.indexNote(7, "Padel ideas", "   ");

        assertThat(storedChunks(SourceType.NOTE, "7")).extracting(NewChunk::text).containsExactly("Padel ideas");
    }

    @Test
    void theTitleIsPartOfWhatGetsEmbedded() {
        indexer.indexNote(7, "Padel", "x");
        indexer.indexNote(8, "Chess", "x");

        List<NewChunk> padel = storedChunks(SourceType.NOTE, "7");
        List<NewChunk> chess = storedChunks(SourceType.NOTE, "8");
        assertThat(padel.get(0).embedding()).isNotEqualTo(chess.get(0).embedding());
    }

    @Test
    void removeNoteDeletesItsChunks() {
        indexer.removeNote(7);

        verify(store).delete(SourceType.NOTE, "7");
    }

    @Test
    void aDayIsSkippedWhenItsSummaryDidNotChange() {
        LocalDate date = LocalDate.of(2026, 9, 24);
        String summary = "Thu 2026-09-24: Gym done 07:00";
        when(store.contentHash(SourceType.DAY, "2026-09-24")).thenReturn(Optional.of(MemoryIndexer.sha256(summary)));

        assertThat(indexer.indexDay(date, "Thu 2026-09-24", summary)).isFalse();
        verify(store, never()).replace(any(), anyString(), any(), anyString(), anyString(), anyList());
    }

    @Test
    void aChangedDayIsStoredAsOneChunkWithItsDate() {
        LocalDate date = LocalDate.of(2026, 9, 24);
        when(store.contentHash(SourceType.DAY, "2026-09-24")).thenReturn(Optional.of("old"));

        assertThat(indexer.indexDay(date, "Thu 2026-09-24", "Thu 2026-09-24: Gym done 07:00")).isTrue();

        verify(store).replace(eq(SourceType.DAY), eq("2026-09-24"), eq(date), eq("Thu 2026-09-24"),
            eq(MemoryIndexer.sha256("Thu 2026-09-24: Gym done 07:00")), anyList());
    }

    @Test
    void notesHaveNoSourceDate() {
        indexer.indexNote(7, "Gym", "body");

        verify(store).replace(eq(SourceType.NOTE), eq("7"), isNull(), eq("Gym"), anyString(), anyList());
    }
}
