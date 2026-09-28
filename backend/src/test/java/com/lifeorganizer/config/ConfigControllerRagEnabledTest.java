package com.lifeorganizer.config;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ConfigController.class)
class ConfigControllerRagEnabledTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TextEmbedder textEmbedder;

    @Test
    void reportsRagEnabledWhenAnEmbedderExists() throws Exception {
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.ragEnabled").value(true));
    }
}
