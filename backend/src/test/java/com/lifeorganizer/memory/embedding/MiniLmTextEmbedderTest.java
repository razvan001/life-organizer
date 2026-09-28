package com.lifeorganizer.memory.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads the real ONNX model once (about a second). No Docker, no network. */
class MiniLmTextEmbedderTest {

    private static MiniLmTextEmbedder embedder;

    @BeforeAll
    static void loadModel() {
        embedder = new MiniLmTextEmbedder();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Test
    void producesVectorsOfTheConfiguredDimension() {
        assertThat(embedder.embed("hello world")).hasSize(TextEmbedder.DIMENSIONS);
    }

    @Test
    void similarMeaningsAreCloserThanUnrelatedOnes() {
        float[] note = embedder.embed("after gym I can't focus on study");
        float[] query = embedder.embed("tired after a workout");
        float[] unrelated = embedder.embed("the invoice for the car repair is due on Friday");

        assertThat(cosine(note, query)).isGreaterThan(cosine(note, unrelated));
    }
}
