package com.lifeorganizer.plan.dto;

import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.engine.SetEnergy;
import com.lifeorganizer.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyEventRequestTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    private static ApplyEventRequest request(String type, String blockId, String reason, String date, String title,
                                             String start, String end, String energy) {
        return new ApplyEventRequest(type, blockId, reason, date, title, start, end, energy);
    }

    @Test
    void mapsEveryEventType() {
        assertThat(request("CANCEL_BLOCK", "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new CancelBlock("b1"));
        assertThat(request("MARK_DONE", "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new MarkDone("b1"));
        assertThat(request("POSTPONE_BLOCK", "b1", "tired", null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new PostponeBlock("b1", "tired"));
        assertThat(request("SET_ENERGY", "b1", null, null, null, null, null, "LOW").toEvent(TODAY))
            .isEqualTo(new SetEnergy("b1", Energy.LOW));
    }

    @Test
    void aBlankPostponeReasonBecomesNull() {
        assertThat(request("POSTPONE_BLOCK", "b1", "  ", null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new PostponeBlock("b1", null));
    }

    @Test
    void addAdhocUsesTheGivenDateOrDefaultsToToday() {
        assertThat(request("ADD_ADHOC", null, null, "2026-09-16", "Padel", "19:00", "20:30", null).toEvent(TODAY))
            .isEqualTo(new AddAdhoc(LocalDate.of(2026, 9, 16), "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));
        assertThat(request("ADD_ADHOC", null, null, null, "Padel", "19:00", "20:30", null).toEvent(TODAY))
            .isEqualTo(new AddAdhoc(TODAY, "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));
    }

    @Test
    void aMissingOrUnknownTypeIsRejected() {
        assertThatThrownBy(() -> request(null, "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("type is required");
        assertThatThrownBy(() -> request("EXPLODE", "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("Unknown event type 'EXPLODE'");
    }

    @Test
    void aMissingBlockIdNamesTheField() {
        assertThatThrownBy(() -> request("MARK_DONE", null, null, null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("blockId is required");
    }

    @Test
    void addAdhocRejectsBadTimesAndAnEndBeforeTheStart() {
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, "Padel", "7pm", "20:30", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("start must be HH:mm, was '7pm'");
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, "Padel", "20:30", "19:00", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("end must be after start");
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, null, "19:00", "20:30", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title is required");
    }

    @Test
    void aBadEnergyNamesTheAllowedValues() {
        assertThatThrownBy(() -> request("SET_ENERGY", "b1", null, null, null, null, null, "TIRED").toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("energy must be one of [LOW, MEDIUM, HIGH], was 'TIRED'");
    }

    @Test
    void overlongTextIsRejected() {
        assertThatThrownBy(() -> request("POSTPONE_BLOCK", "b1", "x".repeat(501), null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("reason must be at most 500 characters");
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, "x".repeat(256), "19:00", "20:00", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title must be at most 255 characters");
    }
}
