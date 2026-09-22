package com.lifeorganizer.plan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanController.class)
class PlanControllerBatchTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlanService planService;

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void appliesAllEventsAndReturnsConcatenatedChanges() throws Exception {
        when(planService.applyEvents(any(), eq(java.time.LocalDate.of(2026, 9, 14))))
            .thenReturn(new AppliedEvent(
                new com.lifeorganizer.domain.DayPlan(java.time.LocalDate.of(2026, 9, 14), List.of(), List.of()),
                List.of("Marked done Gym", "Cancelled Claude certification - slot is now free")));

        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content("""
            {"events":[{"type":"MARK_DONE","blockId":"gym-1"},{"type":"CANCEL_BLOCK","blockId":"cert-1"}]}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value("Marked done Gym"))
            .andExpect(jsonPath("$.changes[1]").value("Cancelled Claude certification - slot is now free"));
    }

    @Test
    void rejectsAnEmptyEventsList() throws Exception {
        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content("""
            {"events":[]}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void returns422WhenAnEventReferencesAnUnknownBlock() throws Exception {
        when(planService.applyEvents(any(), any())).thenThrow(new com.lifeorganizer.error.UnknownBlockException("No block with id 'x'"));

        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content("""
            {"events":[{"type":"MARK_DONE","blockId":"x"}]}
            """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.title").value("Unknown block"));
    }
}
