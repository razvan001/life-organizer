package com.lifeorganizer.memory.index;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static String words(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
    }

    private static int wordCount(String chunk) {
        return chunk.split(" ").length;
    }

    @Test
    void blankOrNullBodyHasNoChunks() {
        assertThat(Chunker.chunk(null)).isEmpty();
        assertThat(Chunker.chunk("  \n\n  ")).isEmpty();
    }

    @Test
    void shortParagraphsArePackedIntoOneChunk() {
        assertThat(Chunker.chunk("after gym\nI can't focus\n\non study")).containsExactly("after gym I can't focus on study");
    }

    @Test
    void paragraphsThatDoNotFitStartANewChunk() {
        String body = words(0, 100) + "\n\n" + words(100, 50);

        List<String> chunks = Chunker.chunk(body);

        assertThat(chunks).containsExactly(words(0, 100), words(100, 50));
    }

    @Test
    void aLongParagraphIsSplitWithOverlap() {
        List<String> chunks = Chunker.chunk(words(0, 250));

        assertThat(chunks).allSatisfy(chunk -> assertThat(wordCount(chunk)).isLessThanOrEqualTo(Chunker.MAX_WORDS));
        assertThat(chunks.get(0)).isEqualTo(words(0, 120));
        assertThat(chunks.get(1)).startsWith("w100 ");
        assertThat(chunks.get(chunks.size() - 1)).endsWith("w249");
    }

    @Test
    void aVeryLongNoteBecomesManyBoundedChunks() {
        List<String> chunks = Chunker.chunk(words(0, 5000));

        assertThat(chunks.size()).isGreaterThan(40);
        assertThat(chunks).allSatisfy(chunk -> assertThat(wordCount(chunk)).isLessThanOrEqualTo(Chunker.MAX_WORDS));
    }
}
