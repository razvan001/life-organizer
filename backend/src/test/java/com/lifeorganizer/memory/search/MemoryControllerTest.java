package com.lifeorganizer.memory.search;

import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemoryController.class)
class MemoryControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MemorySearch search;

    @Test
    void returnsHitsWithSnippetsAndDates() throws Exception {
        when(search.search("tired", 8, null)).thenReturn(List.of(
            new MemoryHit(SourceType.NOTE, "3", null, "Gym", "after gym I can't focus", 0.81),
            new MemoryHit(SourceType.DAY, "2026-09-24", LocalDate.of(2026, 9, 24), "Thu 2026-09-24",
                "Thu 2026-09-24: Gym done 07:00", 0.52)));

        mvc.perform(get("/api/memory/search").param("q", "tired"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sourceType").value("NOTE"))
            .andExpect(jsonPath("$[0].date").doesNotExist())
            .andExpect(jsonPath("$[0].snippet").value("after gym I can't focus"))
            .andExpect(jsonPath("$[1].date").value("2026-09-24"))
            .andExpect(jsonPath("$[1].score").value(0.52));
    }

    @Test
    void longTextIsCutToASnippet() throws Exception {
        when(search.search("x", 8, null)).thenReturn(List.of(
            new MemoryHit(SourceType.NOTE, "3", null, "T", "a".repeat(500), 0.5)));

        mvc.perform(get("/api/memory/search").param("q", "x"))
            .andExpect(jsonPath("$[0].snippet").value("a".repeat(240) + "…"));
    }

    @Test
    void passesKAndSource() throws Exception {
        when(search.search("gym", 3, SourceType.DAY)).thenReturn(List.of());

        mvc.perform(get("/api/memory/search").param("q", "gym").param("k", "3").param("source", "DAY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        verify(search).search("gym", 3, SourceType.DAY);
    }

    @Test
    void blankQueryIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "  "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("q is required"));
        mvc.perform(get("/api/memory/search")).andExpect(status().isBadRequest());
    }

    @Test
    void tooLongQueryIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "a".repeat(501)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("q must be at most 500 characters"));
    }

    @Test
    void kOutOfRangeIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "x").param("k", "0"))
            .andExpect(jsonPath("$.detail").value("k must be between 1 and 20"));
        mvc.perform(get("/api/memory/search").param("q", "x").param("k", "21")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/memory/search").param("q", "x").param("k", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownSourceIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "x").param("source", "EMAIL"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("source must be NOTE or DAY"));
    }
}
