package com.lifeorganizer.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No AgentLlmClient bean (no GROQ_API_KEY): every chat endpoint answers 503. */
@WebMvcTest(ChatController.class)
@Import(ChatControllerNoAiTest.FixedClock.class)
class ChatControllerNoAiTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChatService chat;

    @Test
    void everyEndpointIs503() throws Exception {
        mvc.perform(get("/api/assistant/chat")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/assistant/chat/days")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/assistant/chat/messages/1/apply")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/assistant/chat/messages/1/reject")).andExpect(status().isServiceUnavailable());
    }
}
