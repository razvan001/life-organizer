package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(ChatControllerTest.FixedClock.class)
class ChatControllerTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final ChatMessageDto REPLY =
        new ChatMessageDto(7, "2026-09-30", "ASSISTANT", "Hi!", null, "2026-09-30T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChatService chat;

    @MockitoBean
    AgentLlmClient client;

    @Test
    void threadDefaultsToToday() throws Exception {
        when(chat.thread(LocalDate.of(2026, 9, 30))).thenReturn(List.of(REPLY));

        mvc.perform(get("/api/assistant/chat"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].text").value("Hi!"));
    }

    @Test
    void threadOfAnotherDayAndBadDates() throws Exception {
        when(chat.thread(LocalDate.of(2026, 9, 28))).thenReturn(List.of());

        mvc.perform(get("/api/assistant/chat").param("date", "2026-09-28")).andExpect(status().isOk());
        mvc.perform(get("/api/assistant/chat").param("date", "yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void daysAreListed() throws Exception {
        when(chat.days()).thenReturn(List.of("2026-09-30", "2026-09-29"));

        mvc.perform(get("/api/assistant/chat/days")).andExpect(jsonPath("$[1]").value("2026-09-29"));
    }

    @Test
    void sendReturnsTheAssistantMessage() throws Exception {
        when(chat.send("hello")).thenReturn(REPLY);

        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"  hello \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void sendValidatesTheText() throws Exception {
        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("text is required"));
        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + "a".repeat(2001) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("text must be at most 2000 characters"));
    }

    @Test
    void anUnreachableAssistantIs502() throws Exception {
        when(chat.send("hello")).thenThrow(new ResourceAccessException("down"));

        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.title").value("Assistant unavailable"));
    }

    @Test
    void applyAndReject() throws Exception {
        when(chat.apply(7)).thenReturn(new ApplyEventsResponse("2026-09-30", List.of(), List.of(), List.of("Cancelled Gym")));
        when(chat.reject(8)).thenThrow(new ProposalNotAvailableException("This proposal is applied and can no longer be used"));

        mvc.perform(post("/api/assistant/chat/messages/7/apply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value("Cancelled Gym"));
        mvc.perform(post("/api/assistant/chat/messages/8/reject"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Proposal not available"));
    }
}
