package com.lifeorganizer.plan;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanController.class)
@Import(PlanControllerTest.FixedClock.class)
class PlanControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlanService planService;

    private static DayPlan samplePlan() {
        Block late = new Block("late", TODAY, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.POSTPONED, 2, true, "tired");
        Block early = new Block("early", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
        return new DayPlan(TODAY, List.of(late, early), List.of("a warning"));
    }

    @Test
    void todayReturnsTheBlocksSortedByStartWithFormattedTimes() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(samplePlan());

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-09-14"))
            .andExpect(jsonPath("$.blocks.length()").value(2))
            .andExpect(jsonPath("$.blocks[0].id").value("early"))
            .andExpect(jsonPath("$.blocks[0].start").value("07:00"))
            .andExpect(jsonPath("$.blocks[0].end").value("08:00"))
            .andExpect(jsonPath("$.blocks[0].kind").value("GOAL"))
            .andExpect(jsonPath("$.blocks[0].status").value("PLANNED"))
            .andExpect(jsonPath("$.blocks[0].goalId").value("health-gym"))
            .andExpect(jsonPath("$.blocks[0].postponeCount").value(0))
            .andExpect(jsonPath("$.blocks[0].flagged").value(false))
            .andExpect(jsonPath("$.blocks[1].id").value("late"))
            .andExpect(jsonPath("$.blocks[1].status").value("POSTPONED"))
            .andExpect(jsonPath("$.blocks[1].postponeCount").value(2))
            .andExpect(jsonPath("$.blocks[1].flagged").value(true))
            .andExpect(jsonPath("$.blocks[1].reason").value("tired"))
            .andExpect(jsonPath("$.warnings[0]").value("a warning"));
    }

    @Test
    void todayIsNotFoundBeforeAnySetup() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenThrow(new SetupRequiredException("No setup saved yet"));

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Setup required"))
            .andExpect(jsonPath("$.detail").value("No setup saved yet"));
    }

    @Test
    void postingAnEventPassesItToTheServiceAndReturnsTodayPlusChanges() throws Exception {
        when(planService.applyEvent(any(PlanEvent.class), eq(TODAY)))
            .thenReturn(new AppliedEvent(samplePlan(), List.of("Moved Claude certification 18:00 -> 2026-09-14 21:00")));

        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"POSTPONE_BLOCK\",\"blockId\":\"b1\",\"reason\":\"tired\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-09-14"))
            .andExpect(jsonPath("$.blocks.length()").value(2))
            .andExpect(jsonPath("$.changes[0]").value("Moved Claude certification 18:00 -> 2026-09-14 21:00"));

        ArgumentCaptor<PlanEvent> captor = ArgumentCaptor.forClass(PlanEvent.class);
        verify(planService).applyEvent(captor.capture(), eq(TODAY));
        assertThat(captor.getValue()).isEqualTo(new PostponeBlock("b1", "tired"));
    }

    @Test
    void addAdhocWithoutADateDefaultsToToday() throws Exception {
        when(planService.applyEvent(any(PlanEvent.class), eq(TODAY)))
            .thenReturn(new AppliedEvent(samplePlan(), List.of("Added Padel at 19:00")));

        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ADD_ADHOC\",\"title\":\"Padel\",\"start\":\"19:00\",\"end\":\"20:30\"}"))
            .andExpect(status().isOk());

        ArgumentCaptor<PlanEvent> captor = ArgumentCaptor.forClass(PlanEvent.class);
        verify(planService).applyEvent(captor.capture(), eq(TODAY));
        assertThat(captor.getValue())
            .isEqualTo(new AddAdhoc(TODAY, "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));
    }

    @Test
    void anUnknownEventTypeIsABadRequest() throws Exception {
        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXPLODE\",\"blockId\":\"b1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Unknown event type 'EXPLODE'"));
    }

    @Test
    void aMissingBlockIdIsABadRequest() throws Exception {
        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"MARK_DONE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("blockId is required"));
    }

    @Test
    void anUnknownBlockIsUnprocessable() throws Exception {
        when(planService.applyEvent(any(PlanEvent.class), eq(TODAY)))
            .thenThrow(new UnknownBlockException("No block with id 'nope'"));

        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"nope\"}"))
            .andExpect(status().is(422))
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.title").value("Unknown block"))
            .andExpect(jsonPath("$.detail").value("No block with id 'nope'"));
    }

    @Test
    void malformedAndMissingBodiesAreBadRequests() throws Exception {
        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Malformed request"));

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"));
    }
}
