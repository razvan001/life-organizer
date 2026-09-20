package com.lifeorganizer;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ApiEndToEndTest.FixedClock.class)
class ApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** "Today" is Monday 2026-09-14 for every test. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
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

    // ---- helpers ------------------------------------------------------------------------------

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** POST /api/setup/parse with the user's example and return the raw response body. */
    private String parseExample() throws Exception {
        String body = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        MvcResult result = mvc.perform(post("/api/setup/parse")
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body))
            .andExpect(status().isOk())
            .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Parses the example and saves it exactly as the UI would: by sending the parse response back. */
    private void saveExampleSetup() throws Exception {
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parseExample()))
            .andExpect(status().isOk());
    }

    private String getToday() throws Exception {
        return mvc.perform(get("/api/plan/today"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postEvent(String eventJson) throws Exception {
        return mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON).content(eventJson))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String blockIdByTitle(String todayJson, String title) {
        List<String> ids = JsonPath.read(todayJson, "$.blocks[?(@.title=='" + title + "' && @.status=='PLANNED')].id");
        return ids.get(0);
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    void todayIsNotFoundUntilASetupIsSaved() throws Exception {
        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Setup required"));
    }

    @Test
    void parseSaveAndLoadTheExampleSetup() throws Exception {
        String parsed = parseExample();
        assertThat((Integer) JsonPath.read(parsed, "$.categories.length()")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(parsed, "$.goals.length()")).isEqualTo(9);
        assertThat((Integer) JsonPath.read(parsed, "$.constraints.length()")).isEqualTo(6);
        assertThat((List<String>) JsonPath.read(parsed, "$.warnings")).isEmpty();

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories.length()").value(6));

        mvc.perform(get("/api/setup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories[0].name").value("Career"))
            .andExpect(jsonPath("$.goals.length()").value(9))
            .andExpect(jsonPath("$.constraints[0].label").value("Work"))
            .andExpect(jsonPath("$.constraints[0].start").value("08:30"));
    }

    @Test
    void todayShowsTheExampleDayInOrder() throws Exception {
        saveExampleSetup();

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-09-14"))
            .andExpect(jsonPath("$.blocks.length()").value(9))
            .andExpect(jsonPath("$.blocks[0].title").value("Gym"))
            .andExpect(jsonPath("$.blocks[0].start").value("07:00"))
            .andExpect(jsonPath("$.blocks[2].title").value("Work"))
            .andExpect(jsonPath("$.blocks[2].kind").value("FIXED"))
            .andExpect(jsonPath("$.blocks[3].title").value("Dinner / break"))
            .andExpect(jsonPath("$.blocks[4].title").value("Claude certification"))
            .andExpect(jsonPath("$.blocks[4].start").value("18:00"))
            .andExpect(jsonPath("$.blocks[6].title").value("Dating / events"))
            .andExpect(jsonPath("$.blocks[6].start").value("20:00"))
            .andExpect(jsonPath("$.blocks[8].title").value("Wind down"))
            .andExpect(jsonPath("$.blocks[8].start").value("22:30"))
            .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    void parallelFirstRequestsForTodayAllSucceedAndAgree() throws Exception {
        // React <StrictMode> fires the very first load twice. Without the generation lock the second
        // request dies on a primary-key violation while both try to insert the same blocks.
        saveExampleSetup();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> getToday()));
            }
            List<String> bodies = new ArrayList<>();
            for (Future<String> future : futures) {
                bodies.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
            assertThat((Integer) JsonPath.read(bodies.get(0), "$.blocks.length()")).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doneAndPostponeSurviveAcrossRequests() throws Exception {
        saveExampleSetup();
        String today = getToday();
        String gymId = blockIdByTitle(today, "Gym");
        String certId = blockIdByTitle(today, "Claude certification");
        String workId = blockIdByTitle(today, "Work");

        // mark the gym session done
        String afterDone = postEvent("{\"type\":\"MARK_DONE\",\"blockId\":\"" + gymId + "\"}");
        assertThat((List<String>) JsonPath.read(afterDone, "$.changes")).containsExactly("Marked done Gym");
        assertThat((List<String>) JsonPath.read(afterDone, "$.blocks[?(@.id=='" + gymId + "')].status")).containsExactly("DONE");

        // a fixed block cannot be postponed - the engine says so and nothing changes
        String refused = postEvent("{\"type\":\"POSTPONE_BLOCK\",\"blockId\":\"" + workId + "\"}");
        assertThat((List<String>) JsonPath.read(refused, "$.changes")).containsExactly("Cannot postpone a FIXED block (Work)");

        // postpone the certification block - it moves to the free slot at 21:00
        String afterPostpone = postEvent(
            "{\"type\":\"POSTPONE_BLOCK\",\"blockId\":\"" + certId + "\",\"reason\":\"tired\"}");
        assertThat((List<String>) JsonPath.read(afterPostpone, "$.changes"))
            .containsExactly("Moved Claude certification 18:00 -> 2026-09-14 21:00");

        // a brand-new request reads everything back from the database
        String reloaded = getToday();
        assertThat((List<String>) JsonPath.read(reloaded, "$.blocks[?(@.id=='" + gymId + "')].status")).containsExactly("DONE");
        List<Map<String, Object>> certs = JsonPath.read(reloaded, "$.blocks[?(@.title=='Claude certification')]");
        assertThat(certs).hasSize(2);
        assertThat(certs).extracting(b -> b.get("status")).containsExactlyInAnyOrder("POSTPONED", "PLANNED");
        assertThat(certs).filteredOn(b -> b.get("status").equals("POSTPONED"))
            .extracting(b -> b.get("reason")).containsExactly("tired");
        assertThat(certs).filteredOn(b -> b.get("status").equals("PLANNED"))
            .extracting(b -> b.get("start")).containsExactly("21:00");
    }

    @Test
    void editingTheSetupNeverRewritesAnAlreadyGeneratedDay() throws Exception {
        saveExampleSetup();
        assertThat((Integer) JsonPath.read(getToday(), "$.blocks.length()")).isEqualTo(9);

        String smallSetup = "{\"categories\":[{\"id\":\"health\",\"name\":\"Health\"}],"
            + "\"goals\":[{\"id\":\"health-gym\",\"categoryId\":\"health\",\"name\":\"Gym\",\"priority\":3,"
            + "\"deadline\":null,\"defaultBlockMinutes\":60,\"energy\":\"MEDIUM\",\"preferredTime\":\"ANY\"}],"
            + "\"constraints\":[]}";
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(smallSetup))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goals.length()").value(1));

        // the goals were replaced, but today's persisted blocks are untouched
        assertThat((Integer) JsonPath.read(getToday(), "$.blocks.length()")).isEqualTo(9);
    }

    @Test
    void addAdhocTakesOverTheFreeTimeItOverlaps() throws Exception {
        saveExampleSetup();
        getToday();

        String after = postEvent("{\"type\":\"ADD_ADHOC\",\"title\":\"Padel\",\"start\":\"21:00\",\"end\":\"22:00\"}");

        assertThat((List<String>) JsonPath.read(after, "$.changes")).containsExactly("Added Padel at 21:00");
        List<Map<String, Object>> padel = JsonPath.read(after, "$.blocks[?(@.kind=='ADHOC')]");
        assertThat(padel).hasSize(1);
        // Monday's free block 21:00-22:30 lost the hour Padel takes; only 22:00-22:30 is left of it
        // (the morning gap 08:00-08:30 is untouched).
        List<Map<String, Object>> freeBlocks = JsonPath.read(after, "$.blocks[?(@.kind=='FREE')]");
        assertThat(freeBlocks).extracting(b -> b.get("start") + "-" + b.get("end"))
            .containsExactlyInAnyOrder("08:00-08:30", "22:00-22:30");
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        saveExampleSetup();
        getToday();

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"does-not-exist\"}"))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.title").value("Unknown block"));

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXPLODE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"));

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"categories\":[],\"goals\":[{\"id\":\"x\",\"categoryId\":\"nope\",\"name\":\"X\","
                    + "\"priority\":3,\"defaultBlockMinutes\":60,\"energy\":\"MEDIUM\",\"preferredTime\":\"ANY\"}],"
                    + "\"constraints\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Goal 'x' refers to unknown category 'nope'"));

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON).content("{oops"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void configReportsNoAiOrRagYet() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiEnabled").value(false))
            .andExpect(jsonPath("$.ragEnabled").value(false));
    }
}
