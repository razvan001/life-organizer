package com.lifeorganizer.memory.store;

/** One chunk of text to store, with its embedding. */
public record NewChunk(String text, float[] embedding) {
}
