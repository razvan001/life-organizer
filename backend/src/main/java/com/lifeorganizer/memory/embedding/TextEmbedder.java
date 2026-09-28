package com.lifeorganizer.memory.embedding;

/** Turns text into a fixed-size vector. Implementations must be thread-safe. */
public interface TextEmbedder {

    /** Must match the VECTOR(384) column in V2__memory.sql. */
    int DIMENSIONS = 384;

    float[] embed(String text);
}
