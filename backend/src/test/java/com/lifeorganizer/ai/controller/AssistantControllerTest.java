package com.lifeorganizer.ai.controller;

import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.service.CommandInterpreter;
import com.lifeorganizer.config.GlobalExceptionHandler;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssistantController.class)
@Import({AssistantControllerTest.FixedClock.class, GlobalExceptionHandler.class})
class AssistantControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CommandInterpreter commandInterpreter;

    @MockitoBean
    PlanService planService;

    private DayPlan emptyPlan() {
        return new DayPlan(TODAY, List.of(), List.of());
    }

    @Test
    void returnsAProposalWhenUnderstood() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        ApplyEventRequest event = new ApplyEventRequest("CANCEL_BLOCK", "padel-1", null, null, null, null, null, null);
        when(commandInterpreter.interpret(eq("padel got canceled"), eq(TODAY), any()))
            .thenReturn(InterpretedCommand.understood("Padel is cancelled", List.of(event)));
        when(planService.simulateEvents(any(), eq(TODAY)))
            .thenReturn(new AppliedEvent(emptyPlan(), List.of("Cancelled Padel - slot is now free")));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"padel got canceled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(true))
            .andExpect(jsonPath("$.rationale").value("Padel is cancelled"))
            .andExpect(jsonPath("$.changes[0]").value("Cancelled Padel - slot is now free"));
    }

    @Test
    void returnsAMessageWhenNotUnderstood() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        when(commandInterpreter.interpret(any(), eq(TODAY), any()))
            .thenReturn(InterpretedCommand.notUnderstood("I couldn't find a matching block"));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"do the thing\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(false))
            .andExpect(jsonPath("$.message").value("I couldn't find a matching block"));
    }

    @Test
    void rejectsBlankText() throws Exception {
        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void rejectsTextLongerThan2000Characters() throws Exception {
        String tooLong = "a".repeat(2001);

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void returns502WhenTheClientThrows() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        when(commandInterpreter.interpret(any(), eq(TODAY), any())).thenThrow(new ResourceAccessException("boom"));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.title").value("Assistant unavailable"));
    }

    @Test
    void returns503WhenAiIsNotConfigured() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        when(commandInterpreter.interpret(any(), eq(TODAY), any()))
            .thenThrow(new AiNotConfiguredException("AI features are not configured"));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("AI features are not configured"));
    }
}
