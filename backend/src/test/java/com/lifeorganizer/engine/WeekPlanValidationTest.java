package com.lifeorganizer.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeekPlanValidationTest {

    @Test
    void nullUnitsByDateThrowsNullPointerException() {
        assertThatThrownBy(() -> new WeekPlan(null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("unitsByDate");
    }

    @Test
    void nullWarningsThrowsNullPointerException() {
        assertThatThrownBy(() -> new WeekPlan(java.util.Map.of(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("warnings");
    }
}
