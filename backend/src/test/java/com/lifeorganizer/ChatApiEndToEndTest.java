package com.lifeorganizer;

import com.jayway.jsonpath.JsonPath;
import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolCall;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.plan.PlanService;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chat flow with a scripted model: read today's plan, propose cancelling or postponing the first goal
 * block, answer in text; then apply the proposal through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({ChatApiEndToEndTest.FixedClock.class, ChatApiEndToEndTest.ScriptedModel.class})
class ChatApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** "Today" is Monday 2026-09-14. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    /**
     * Works from the "Current data" the app loads into every turn (today's plan and the week so far), like the real
     * model should. A review gets a plain summary; otherwise call 1 is propose_changes on the first GOAL block (postpone
     * if the user said "postpone", else cancel). Call 2: a text answer.
     */
    @TestConfiguration
    static class ScriptedModel {
        @Bean
        @Primary
        AgentLlmClient scriptedAgentClient() {
            return (messages, tools) -> {
                List<AgentMessage> toolResults = messages.stream().filter(m -> m.role() == AgentMessage.Role.TOOL).toList();
                String userText = messages.stream().filter(m -> m.role() == AgentMessage.Role.USER)
                    .reduce((first, second) -> second).orElseThrow().content();
                String data = messages.stream()
                    .filter(m -> m.role() == AgentMessage.Role.SYSTEM && m.content().startsWith("Current data"))
                    .findFirst().orElseThrow().content();
                if (userText.contains("review")) {
                    if (!data.contains("System design (id career-system-design, priority 3")) {
                        throw new IllegalStateException("week progress is missing from the current data: " + data);
                    }
                    return new AgentReply("Gym has been postponed twice; System design is on track.", List.of());
                }
                if (toolResults.isEmpty()) {
                    String todaysPlan = data.substring(data.indexOf('{'), data.lastIndexOf('}') + 1);
                    List<String> goalIds = JsonPath.read(todaysPlan, "$.blocks[?(@.kind == 'GOAL')].id");
                    String type = userText.contains("postpone") ? "POSTPONE_BLOCK" : "CANCEL_BLOCK";
                    return new AgentReply(null, List.of(new ToolCall("c2", "propose_changes",
                        "{\"rationale\":\"Taking it off today\",\"events\":[{\"type\":\"" + type + "\",\"blockId\":\""
                            + goalIds.get(0) + "\"}]}")));
                }
                return new AgentReply("I proposed a change.", List.of());
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlanService planService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table chat_message, day_warning, block, plan_constraint, goal, category restart identity");
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private void saveExampleSetup() throws Exception {
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(ExampleSetup.payloadJson()))
            .andExpect(status().isOk());
    }

    private String send(String text) throws Exception {
        return mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aProposalCanBeAppliedAndTheThreadIsKept() throws Exception {
        saveExampleSetup();

        String reply = send("my first session is off today");
        Integer id = JsonPath.read(reply, "$.id");
        String blockId = JsonPath.read(reply, "$.proposal.events[0].blockId");
        mvc.perform(get("/api/assistant/chat"))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].proposal.status").value("PENDING"))
            .andExpect(jsonPath("$[1].text").value("I proposed a change."));

        mvc.perform(post("/api/assistant/chat/messages/" + id + "/apply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value(containsString("Cancelled")));

        mvc.perform(get("/api/plan/today"))
            .andExpect(jsonPath("$.blocks[?(@.id == '" + blockId + "')].status").value("CANCELLED"));
        mvc.perform(get("/api/assistant/chat")).andExpect(jsonPath("$[1].proposal.status").value("APPLIED"));
        mvc.perform(post("/api/assistant/chat/messages/" + id + "/apply")).andExpect(status().isConflict());
        mvc.perform(get("/api/assistant/chat/days")).andExpect(jsonPath("$[0]").value("2026-09-14"));
    }

    @Test
    void proposingAPostponeAgainstAFreshWeekWorks() throws Exception {
        saveExampleSetup();
        // Only today exists; the postpone simulation must lazily generate the rest of the week (the Phase 2
        // read-only-transaction bug would 500 here).
        mvc.perform(get("/api/plan/today")).andExpect(status().isOk());

        String reply = send("please postpone my first session");

        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(reply, "$.proposal.events[0].type"))
            .isEqualTo("POSTPONE_BLOCK");
    }

    @Test
    void aWeeklyReviewIsASummaryWithoutAProposal() throws Exception {
        saveExampleSetup();

        String reply = send("please review my week");

        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(reply, "$.text"))
            .isEqualTo("Gym has been postponed twice; System design is on track.");
        org.assertj.core.api.Assertions.assertThat((Object) JsonPath.read(reply, "$.proposal")).isNull();
    }
}
