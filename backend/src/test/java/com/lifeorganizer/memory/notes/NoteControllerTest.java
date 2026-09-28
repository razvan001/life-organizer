package com.lifeorganizer.memory.notes;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.NoteNotFoundException;
import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NoteController.class)
class NoteControllerTest {

    private static final NoteDto GYM = new NoteDto(1, "Gym", "body", "2026-09-28T10:00:00Z", "2026-09-28T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NoteService notes;

    @Test
    void listsNotes() throws Exception {
        when(notes.list()).thenReturn(List.of(GYM));

        mvc.perform(get("/api/notes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Gym"))
            .andExpect(jsonPath("$[0].updatedAt").value("2026-09-28T10:00:00Z"));
    }

    @Test
    void createReturns201() throws Exception {
        when(notes.create(new NoteRequest("Gym", "body"))).thenReturn(GYM);

        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Gym\",\"body\":\"body\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void updateReturnsTheNote() throws Exception {
        when(notes.update(1, new NoteRequest("Gym", "new"))).thenReturn(GYM);

        mvc.perform(put("/api/notes/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Gym\",\"body\":\"new\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/notes/1")).andExpect(status().isNoContent());

        verify(notes).delete(1);
    }

    @Test
    void unknownNoteIs404() throws Exception {
        doThrow(new NoteNotFoundException(9)).when(notes).delete(9);

        mvc.perform(delete("/api/notes/9"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Note not found"));
    }

    @Test
    void invalidInputIs400() throws Exception {
        when(notes.create(any(NoteRequest.class))).thenThrow(new InvalidRequestException("title is required"));

        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("title is required"));
    }

    @Test
    void aNonNumericIdIs400() throws Exception {
        mvc.perform(delete("/api/notes/abc")).andExpect(status().isBadRequest());
    }
}
