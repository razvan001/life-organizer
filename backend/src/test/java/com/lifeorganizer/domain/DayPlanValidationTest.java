package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DayPlanValidationTest {

    @Test
    void nullDateThrowsNullPointerException() {
        assertThatThrownBy(() -> new DayPlan(null, List.of(), List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("date");
    }

    @Test
    void nullBlocksThrowsNullPointerException() {
        assertThatThrownBy(() -> new DayPlan(LocalDate.of(2026, 9, 17), null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("blocks");
    }

    @Test
    void nullWarningsThrowsNullPointerException() {
        assertThatThrownBy(() -> new DayPlan(LocalDate.of(2026, 9, 17), List.of(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("warnings");
    }
}
