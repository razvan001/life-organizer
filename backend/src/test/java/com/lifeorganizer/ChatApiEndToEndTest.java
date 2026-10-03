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
     * Call 1: get_today_plan. Call 2: propose_changes on the first GOAL block (postpone if the user said
     * "postpone", else cancel). Call 3: a text answer.
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
                if (userText.contains("review")) {
                    if (toolResults.isEmpty()) {
                        return new AgentReply(null, List.of(new ToolCall("r1", "get_week_progress", "{}")));
                    }
                    if (toolResults.size() == 1) {
                        return new AgentReply(null, List.of(new ToolCall("r2", "propose_priorities",
                            "{\"rationale\":\"More system design\",\"changes\":[{\"goalId\":\"career-system-design\","
                                + "\"priority\":5,\"reason\":\"interview soon\"}]}")));
                    }
                    return new AgentReply("I suggest raising System design.", List.of());
                }
                if (toolResults.isEmpty()) {
                    return new AgentReply(null, List.of(new ToolCall("c1", "get_today_plan", "{}")));
                }
                if (toolResults.size() == 1) {
                    List<String> goalIds = JsonPath.read(toolResults.get(0).content(), "$.blocks[?(@.kind == 'GOAL')].id");
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
        String setupJson = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        String parsed = mvc.perform(post("/api/setup/parse").contentType(MediaType.APPLICATION_JSON).content(setupJson))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed)).andExpect(status().isOk());
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
    void aPriorityProposalUpdatesTheGoalAndReplansUntouchedDays() throws Exception {
        saveExampleSetup();
        mvc.perform(get("/api/plan/today")).andExpect(status().isOk());
        planService.getOrGenerate(java.time.LocalDate.of(2026, 9, 16)); // Wednesday, stored and untouched

        String reply = send("please review my week");
        Integer id = JsonPath.read(reply, "$.id");
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(reply, "$.proposal.kind")).isEqualTo("PRIORITIES");

        mvc.perform(post("/api/assistant/chat/messages/" + id + "/apply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value("System design: priority 3 -> 5"))
            .andExpect(jsonPath("$.changes[1]").value("Re-planned Wed 2026-09-16"))
            .andExpect(jsonPath("$.date").value("2026-09-14"));

        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("select priority from goal where id = 'career-system-design'", Integer.class)).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("select count(*) from block where block_date = '2026-09-15'", Integer.class)).isZero();
        mvc.perform(get("/api/assistant/chat")).andExpect(jsonPath("$[1].proposal.status").value("APPLIED"));
    }

    @Test
    void aStalePriorityProposalExpires() throws Exception {
        saveExampleSetup();
        Integer id = JsonPath.read(send("please review my week"), "$.id");
        jdbc.update("update goal set priority = 4 where id = 'career-system-design'");

        mvc.perform(post("/api/assistant/chat/messages/" + id + "/apply"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Your goals changed since this proposal"));

        mvc.perform(get("/api/assistant/chat")).andExpect(jsonPath("$[1].proposal.status").value("EXPIRED"));
    }
}
