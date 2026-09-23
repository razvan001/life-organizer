package com.lifeorganizer;

import com.lifeorganizer.ai.client.LlmCommandClient;
import com.lifeorganizer.ai.model.LlmCommandResult;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.ai.model.ProposedEventType;
import com.lifeorganizer.support.ExampleSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full HTTP flow (propose, then apply) with a fake {@link LlmCommandClient} standing
 * in for a real API call - no network, no API key needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({AssistantApiEndToEndTest.FixedClock.class, AssistantApiEndToEndTest.FakeClaudeConfig.class})
class AssistantApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    /**
     * A fake standing in for the real Groq call: cancels whatever block id it is asked about, unless
     * the user's text asks to postpone it, in which case it proposes a postpone instead.
     */
    @TestConfiguration
    static class FakeClaudeConfig {
        @Bean
        @Primary
        LlmCommandClient fakeLlmCommandClient() {
            return (system, user) -> {
                String blockId = user.lines()
                    .filter(line -> line.startsWith("- id="))
                    .map(line -> line.substring(5, line.indexOf(' ', 5)))
                    .findFirst()
                    .orElseThrow();
                if (user.toLowerCase().contains("postpone")) {
                    ProposedEvent postpone = new ProposedEvent(ProposedEventType.POSTPONE_BLOCK, blockId,
                        "tired", null, null, null, null, null);
                    return new LlmCommandResult(true, "Postponing it", null, List.of(postpone));
                }
                ProposedEvent cancel = new ProposedEvent(ProposedEventType.CANCEL_BLOCK, blockId,
                    null, null, null, null, null, null);
                return new LlmCommandResult(true, "Cancelling it", null, List.of(cancel));
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table day_warning, block, plan_constraint, goal, category restart identity");
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void proposingThenApplyingACommandCancelsTheTargetBlock() throws Exception {
        String setupJson = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        String parsed = mvc.perform(post("/api/setup/parse").contentType(MediaType.APPLICATION_JSON).content(setupJson))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed))
            .andExpect(status().isOk());

        String proposeResponse = mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"gym got canceled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(true))
            .andReturn().getResponse().getContentAsString();

        List<?> events = read(proposeResponse, "$.events");
        assertThat(events).hasSize(1);
        String applyBody = "{\"events\":" + tools.jackson.databind.json.JsonMapper.builder().build()
            .writeValueAsString(events) + "}";

        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value(org.hamcrest.Matchers.containsString("Cancelled")));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything else\"}"));
    }

    @Test
    void proposingAPostponeAgainstAFreshWeekDoesNotFail() throws Exception {
        String setupJson = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        String parsed = mvc.perform(post("/api/setup/parse").contentType(MediaType.APPLICATION_JSON).content(setupJson))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed))
            .andExpect(status().isOk());

        // Generate only today (Monday, per the fixed clock) - the bug is specifically about simulateEvents
        // lazily generating a *different*, not-yet-generated day (the rest of the week, while looking for
        // room to postpone into) inside what used to be a read-only transaction.
        mvc.perform(get("/api/plan/today")).andExpect(status().isOk());

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"I'm tired, postpone it\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(true));
    }
}
