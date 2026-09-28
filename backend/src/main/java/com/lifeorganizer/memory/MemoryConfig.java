package com.lifeorganizer.memory;

import com.lifeorganizer.memory.embedding.MiniLmTextEmbedder;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The embedding model is always available: it runs locally and needs no key. */
@Configuration
public class MemoryConfig {

    @Bean
    public TextEmbedder textEmbedder() {
        return new MiniLmTextEmbedder();
    }
}
