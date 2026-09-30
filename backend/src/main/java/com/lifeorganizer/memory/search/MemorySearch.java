package com.lifeorganizer.memory.search;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Semantic search over notes and past days. The agent (Phase 3b) will call this as its search_memory tool. */
@Service
public class MemorySearch {

    private static final Logger log = LoggerFactory.getLogger(MemorySearch.class);

    private final TextEmbedder embedder;
    private final MemoryChunkStore store;

    public MemorySearch(TextEmbedder embedder, MemoryChunkStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    /** Chunks fetched per wanted result, so deduplicating by source still leaves k results in most cases. */
    static final int OVER_FETCH = 4;

    public List<MemoryHit> search(String query, int k, SourceType filter) {
        List<MemoryHit> chunks = store.search(embedder.embed(query), k * OVER_FETCH, filter);
        // Results are per chunk; keep each source's best chunk so one long note cannot fill every slot.
        Map<String, MemoryHit> bestPerSource = new LinkedHashMap<>();
        for (MemoryHit chunk : chunks) {
            bestPerSource.putIfAbsent(chunk.sourceType() + ":" + chunk.sourceId(), chunk);
        }
        List<MemoryHit> hits = bestPerSource.values().stream().limit(k).toList();
        log.info("Memory search: query.length={} k={} source={} hits={} topScore={}", query.length(), k, filter,
            hits.size(), hits.isEmpty() ? "-" : String.format(Locale.ROOT, "%.3f", hits.get(0).score()));
        return hits;
    }
}
