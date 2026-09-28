package com.lifeorganizer.memory.index;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.NewChunk;
import com.lifeorganizer.memory.store.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/** Turns notes and day summaries into embedded chunks in the memory store. */
@Service
public class MemoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexer.class);

    private final TextEmbedder embedder;
    private final MemoryChunkStore store;

    public MemoryIndexer(TextEmbedder embedder, MemoryChunkStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    /** Always re-indexes: note writes are rare and the user expects the new text to be searchable at once. */
    public void indexNote(long noteId, String title, String body) {
        List<String> texts = Chunker.chunk(body);
        if (texts.isEmpty()) {
            texts = List.of(title); // an empty note is still findable by its title
        }
        // The title goes into every embedding so a chunk that never names the topic still matches it.
        List<NewChunk> chunks = texts.stream()
            .map(text -> new NewChunk(text, embedder.embed(title + "\n" + text)))
            .toList();
        store.replace(SourceType.NOTE, Long.toString(noteId), null, title, sha256(title + "\n" + body), chunks);
        log.debug("Indexed note {} as {} chunk(s)", noteId, chunks.size());
    }

    public void removeNote(long noteId) {
        store.delete(SourceType.NOTE, Long.toString(noteId));
    }

    /** Stores one day's summary as a single chunk; returns false when the stored summary is identical. */
    public boolean indexDay(LocalDate date, String title, String summary) {
        String sourceId = date.toString();
        String hash = sha256(summary);
        if (store.contentHash(SourceType.DAY, sourceId).filter(hash::equals).isPresent()) {
            return false;
        }
        store.replace(SourceType.DAY, sourceId, date, title, hash,
            List.of(new NewChunk(summary, embedder.embed(summary))));
        return true;
    }

    public void removeDay(LocalDate date) {
        store.delete(SourceType.DAY, date.toString());
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
