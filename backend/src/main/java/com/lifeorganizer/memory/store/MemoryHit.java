package com.lifeorganizer.memory.store;

import java.time.LocalDate;

/** One search result. score is cosine similarity: 1 means same direction. */
public record MemoryHit(SourceType sourceType, String sourceId, LocalDate sourceDate, String title, String text,
                        double score) {
}
