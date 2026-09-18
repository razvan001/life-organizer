package com.lifeorganizer.engine;

import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.Energy;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanEventValidationTest {

    @Test
    void cancelBlockNullBlockIdThrowsNullPointerException() {
        assertThatThrownBy(() -> new CancelBlock(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("blockId");
    }

    @Test
    void setEnergyNullBlockIdThrowsNullPointerException() {
        assertThatThrownBy(() -> new SetEnergy(null, Energy.LOW))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("blockId");
    }

    @Test
    void setEnergyNullLevelThrowsNullPointerException() {
        assertThatThrownBy(() -> new SetEnergy("blockId", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("level");
    }

    @Test
    void addAdhocEndBeforeStartThrowsIllegalArgumentException() {
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(9, 0);
        assertThatThrownBy(() -> new AddAdhoc(LocalDate.of(2026, 9, 18), "title", start, end))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("end must be after start");
    }

    @Test
    void replanResultPreservesLinkedHashMapInsertionOrder() {
        LocalDate wednesday = LocalDate.of(2026, 9, 16); // Wednesday
        LocalDate monday = LocalDate.of(2026, 9, 14);    // Monday
        LocalDate friday = LocalDate.of(2026, 9, 18);    // Friday

        Map<LocalDate, DayPlan> updatedPlans = new LinkedHashMap<>();
        updatedPlans.put(wednesday, null);
        updatedPlans.put(monday, null);
        updatedPlans.put(friday, null);

        ReplanResult result = new ReplanResult(updatedPlans, List.of());

        assertThat(result.updatedPlans().keySet())
            .containsExactly(wednesday, monday, friday);
    }

    @Test
    void replanResultNullChangesThrowsNullPointerException() {
        assertThatThrownBy(() -> new ReplanResult(Map.of(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("changes");
    }
}
