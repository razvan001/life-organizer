package com.lifeorganizer.config;

import com.lifeorganizer.ai.client.LlmClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
@Import(LlmClientConfig.class)
@TestPropertySource(properties = "GROQ_API_KEY=gsk_test-dummy-key")
class ConfigControllerAiEnabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsAiEnabledWhenTheAgentClientBeanExists() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiEnabled").value(true))
            .andExpect(jsonPath("$.ragEnabled").value(false));
    }
}
