package com.lifeorganizer.config;

import com.lifeorganizer.ai.client.ClaudeClientConfig;
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
@Import(ClaudeClientConfig.class)
@TestPropertySource(properties = "ANTHROPIC_API_KEY=sk-ant-test-dummy-key")
class ConfigControllerAiEnabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsAiEnabledWhenAnAnthropicClientBeanExists() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiEnabled").value(true))
            .andExpect(jsonPath("$.ragEnabled").value(false));
    }
}
