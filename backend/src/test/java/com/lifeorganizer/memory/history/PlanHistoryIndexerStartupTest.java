package com.lifeorganizer.memory.history;

import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.persistence.BlockRepository;
import com.lifeorganizer.persistence.PlanStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanHistoryIndexerStartupTest {

    @Test
    void aFailureAtStartupIsLoggedNotThrown() {
        BlockRepository blocks = mock(BlockRepository.class);
        when(blocks.findDistinctDatesBetween(any(), any())).thenThrow(new IllegalStateException("db down"));
        PlanHistoryIndexer indexer = new PlanHistoryIndexer(blocks, mock(PlanStore.class), mock(MemoryIndexer.class),
            Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC));

        assertThatCode(indexer::onStartup).doesNotThrowAnyException();
    }
}
