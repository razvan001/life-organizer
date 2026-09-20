package com.lifeorganizer.setup.api;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupParseRequest;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import com.lifeorganizer.setup.api.dto.SetupPayload;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    private static final CategoryDto HEALTH = new CategoryDto("health", "Health");
    private static final GoalDto GYM = new GoalDto("health-gym", "health", "Gym", 3, null, 60, "MEDIUM", "ANY");
    private static final ConstraintDto WORK = new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00",
        List.of("MONDAY", "TUESDAY"), null, null, null, null, null, null);
    private static final SetupPayload PAYLOAD = new SetupPayload(List.of(HEALTH), List.of(GYM), List.of(WORK));

    private static final String PAYLOAD_JSON = """
        {"categories":[{"id":"health","name":"Health"}],
         "goals":[{"id":"health-gym","categoryId":"health","name":"Gym","priority":3,"deadline":null,
                   "defaultBlockMinutes":60,"energy":"MEDIUM","preferredTime":"ANY"}],
         "constraints":[{"type":"FIXED_BLOCK","label":"Work","start":"08:30","end":"17:00",
                         "days":["MONDAY","TUESDAY"]}]}
        """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SetupService setupService;

    @Test
    void parseReturnsTheStructuredSetupAndWarnings() throws Exception {
        when(setupService.parse(new SetupParseRequest("goals text", "constraints text")))
            .thenReturn(new SetupParseResponse(List.of(HEALTH), List.of(GYM), List.of(WORK), List.of("a warning")));

        mvc.perform(post("/api/setup/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goalsText\":\"goals text\",\"constraintsText\":\"constraints text\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories[0].id").value("health"))
            .andExpect(jsonPath("$.goals[0].id").value("health-gym"))
            .andExpect(jsonPath("$.goals[0].priority").value(3))
            .andExpect(jsonPath("$.goals[0].energy").value("MEDIUM"))
            .andExpect(jsonPath("$.constraints[0].type").value("FIXED_BLOCK"))
            .andExpect(jsonPath("$.constraints[0].start").value("08:30"))
            .andExpect(jsonPath("$.constraints[0].days[1]").value("TUESDAY"))
            .andExpect(jsonPath("$.warnings[0]").value("a warning"));
    }

    @Test
    void parseOfBrokenTextIsABadRequest() throws Exception {
        when(setupService.parse(any(SetupParseRequest.class)))
            .thenThrow(new InvalidRequestException("Goal 'Orphan' has no parent category"));

        mvc.perform(post("/api/setup/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goalsText\":\"x\",\"constraintsText\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Goal 'Orphan' has no parent category"));
    }

    @Test
    void getReturnsTheStoredSetup() throws Exception {
        when(setupService.load()).thenReturn(PAYLOAD);

        mvc.perform(get("/api/setup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories.length()").value(1))
            .andExpect(jsonPath("$.goals[0].name").value("Gym"))
            .andExpect(jsonPath("$.constraints[0].label").value("Work"));
    }

    @Test
    void putSavesThePayloadAndReturnsTheStoredSetup() throws Exception {
        when(setupService.load()).thenReturn(PAYLOAD);

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goals[0].id").value("health-gym"));

        verify(setupService).save(PAYLOAD);
    }

    @Test
    void putIgnoresUnknownPropertiesSoTheParseResponseCanBeSentBackUnchanged() throws Exception {
        when(setupService.load()).thenReturn(PAYLOAD);
        String parseResponseShape = PAYLOAD_JSON.replaceFirst("\\{", "{\"warnings\":[\"kept?\"],");

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parseResponseShape))
            .andExpect(status().isOk());

        verify(setupService).save(PAYLOAD);
    }

    @Test
    void putOfInvalidContentIsABadRequest() throws Exception {
        doThrow(new InvalidRequestException("Duplicate goal id 'x'")).when(setupService).save(any(SetupPayload.class));

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Duplicate goal id 'x'"));
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"));
    }
}
