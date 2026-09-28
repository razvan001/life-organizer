package com.lifeorganizer.support;

import com.lifeorganizer.memory.embedding.TextEmbedder;

import java.util.Locale;

/**
 * Deterministic stand-in for the real model: each word adds 1 to one of 384 buckets, so texts that share
 * words point in similar directions. Never returns a zero vector (cosine distance would be NaN).
 */
public final class HashingTextEmbedder implements TextEmbedder {

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        boolean any = false;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            any = true;
        }
        if (!any) {
            vector[0] = 1f;
        }
        return vector;
    }
}
