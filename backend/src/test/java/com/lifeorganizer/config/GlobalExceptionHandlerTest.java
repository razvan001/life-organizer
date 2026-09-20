package com.lifeorganizer.config;

import com.lifeorganizer.plan.PlanController;
import com.lifeorganizer.plan.PlanService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The catch-all handler must not swallow framework errors that already carry a proper status. */
@WebMvcTest(PlanController.class)
@Import(GlobalExceptionHandlerTest.FixedClock.class)
class GlobalExceptionHandlerTest {

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

    @Test
    void anUnsupportedHttpMethodIsA405ProblemNotA500() throws Exception {
        mvc.perform(put("/api/plan/today"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void anUnsupportedMediaTypeIsA415ProblemNotA500() throws Exception {
        mvc.perform(post("/api/plan/today/events").contentType(MediaType.TEXT_PLAIN).content("hello"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void anUnknownPathIsA404NotA500() throws Exception {
        mvc.perform(get("/api/does-not-exist"))
            .andExpect(status().isNotFound());
    }

    @Test
    void anUnexpectedExceptionIsAGenericProblemThatLeaksNothing() throws Exception {
        when(planService.getOrGenerate(TODAY))
            .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/db exploded"));

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.title").value("Unexpected error"))
            .andExpect(jsonPath("$.detail").value("Something went wrong"))
            .andExpect(content().string(not(containsString("secret-host"))))
            .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}
