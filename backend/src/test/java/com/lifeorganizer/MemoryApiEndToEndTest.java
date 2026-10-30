package com.lifeorganizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notes API + real MiniLM model + pgvector: the spec's success example. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MemoryApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table memory_chunk, note restart identity");
    }

    private void note(String title, String body) throws Exception {
        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void theGymNoteRanksFirstForATiredQuery() throws Exception {
        note("Energy", "after gym I can't focus on study");
        note("Car", "the invoice for the car repair is due on Friday");
        note("Recipes", "lentil soup needs cumin and lemon");

        mvc.perform(get("/api/memory/search").param("q", "tired after a workout"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Energy"))
            .andExpect(jsonPath("$[0].sourceType").value("NOTE"))
            .andExpect(jsonPath("$.length()").value(1)); // the car and recipe notes fall below the floor
    }

    @Test
    void searchWithNothingIndexedIsAnEmptyList() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "anything"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void configReportsRagEnabled() throws Exception {
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.ragEnabled").value(true));
    }
}
