package com.lifeorganizer.memory.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;

/**
 * all-MiniLM-L6-v2 (quantized ONNX) running inside the JVM: no API key, no network. The model reads at most
 * about 256 tokens per text, which is why notes are chunked before embedding.
 */
public class MiniLmTextEmbedder implements TextEmbedder {

    private final EmbeddingModel model = new AllMiniLmL6V2QuantizedEmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }
}
