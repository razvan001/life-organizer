package com.lifeorganizer.ai.agent;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposedEventValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Block GYM = new Block("gym-1", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
        BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
    private static final Block FREE = new Block("free-2026-09-30-08:00", TODAY, LocalTime.of(8, 0),
        LocalTime.of(23, 0), "Free", BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
    private static final List<Block> BLOCKS = List.of(GYM, FREE);

    private static ApplyEventRequest cancel(String blockId) {
        return new ApplyEventRequest("CANCEL_BLOCK", blockId, null, null, null, null, null, null);
    }

    private static ApplyEventRequest adhoc(String date) {
        return new ApplyEventRequest("ADD_ADHOC", null, null, date, "Movie", "20:00", "22:00", null);
    }

    @Test
    void turnsValidRequestsIntoEngineEvents() {
        assertThat(ProposedEventValidator.validate(List.of(cancel("gym-1"), adhoc(null)), TODAY, BLOCKS))
            .satisfiesExactly(
                event -> assertThat(event).isEqualTo(new CancelBlock("gym-1")),
                event -> assertThat(event).isInstanceOf(AddAdhoc.class));
    }

    @Test
    void anAdhocForTodaysDateIsAllowed() {
        assertThat(ProposedEventValidator.validate(List.of(adhoc("2026-09-30")), TODAY, BLOCKS)).hasSize(1);
    }

    @Test
    void rejectsAnUnknownBlock() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(cancel("nope")), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("unknown blockId 'nope': use an id from today's plan");
    }

    @Test
    void rejectsAFreeBlock() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(cancel(FREE.id())), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("block 'free-2026-09-30-08:00' is free time, not something that can be changed");
    }

    @Test
    void rejectsAnAdhocOnAnotherDay() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(adhoc("2026-10-01")), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("ADD_ADHOC can only add to today (2026-09-30)");
    }

    @Test
    void rejectsZeroOrMoreThanFiveEvents() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(), TODAY, BLOCKS))
            .hasMessage("propose between 1 and 5 events");
        assertThatThrownBy(() -> ProposedEventValidator.validate(Collections.nCopies(6, cancel("gym-1")), TODAY, BLOCKS))
            .hasMessage("propose between 1 and 5 events");
        assertThatThrownBy(() -> ProposedEventValidator.validate(null, TODAY, BLOCKS))
            .hasMessage("propose between 1 and 5 events");
    }

    @Test
    void passesOnTheEnginesOwnRules() {
        ApplyEventRequest noTitle = new ApplyEventRequest("ADD_ADHOC", null, null, null, null, "20:00", "22:00", null);
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(noTitle), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title is required");
    }
}
