package com.lifeorganizer.memory.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a note body into chunks the embedding model can read whole (MiniLM stops near 256 tokens).
 * Paragraphs are packed together up to MAX_WORDS; a paragraph longer than that is cut into windows that
 * overlap by OVERLAP_WORDS so a sentence on a boundary still appears whole in one of them.
 */
public final class Chunker {

    static final int MAX_WORDS = 120;
    static final int OVERLAP_WORDS = 20;

    private Chunker() {
    }

    public static List<String> chunk(String body) {
        List<String> chunks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return chunks;
        }
        List<String> current = new ArrayList<>();
        for (String paragraph : body.split("\\n\\s*\\n")) {
            List<String> words = words(paragraph);
            if (words.isEmpty()) {
                continue;
            }
            if (words.size() > MAX_WORDS) {
                flush(current, chunks);
                for (int start = 0; ; start += MAX_WORDS - OVERLAP_WORDS) {
                    int end = Math.min(words.size(), start + MAX_WORDS);
                    chunks.add(String.join(" ", words.subList(start, end)));
                    if (end == words.size()) {
                        break;
                    }
                }
            } else {
                if (current.size() + words.size() > MAX_WORDS) {
                    flush(current, chunks);
                }
                current.addAll(words);
            }
        }
        flush(current, chunks);
        return chunks;
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.trim().split("\\s+")).filter(word -> !word.isEmpty()).toList();
    }

    private static void flush(List<String> current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(String.join(" ", current));
            current.clear();
        }
    }
}
