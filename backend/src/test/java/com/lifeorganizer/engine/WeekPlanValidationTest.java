package com.lifeorganizer.engine;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void preservesLinkedHashMapInsertionOrder() {
        // Build a LinkedHashMap with dates in non-alphabetical order
        LocalDate wednesday = LocalDate.of(2026, 9, 16); // Wednesday
        LocalDate monday = LocalDate.of(2026, 9, 14);    // Monday
        LocalDate friday = LocalDate.of(2026, 9, 18);    // Friday

        Map<LocalDate, List<Unit>> unitsByDate = new LinkedHashMap<>();
        unitsByDate.put(wednesday, List.of());
        unitsByDate.put(monday, List.of());
        unitsByDate.put(friday, List.of());

        // Create a WeekPlan with the LinkedHashMap
        WeekPlan weekPlan = new WeekPlan(unitsByDate, List.of());

        // Assert insertion order is preserved (Wednesday, Monday, Friday)
        assertThat(weekPlan.unitsByDate().keySet())
            .containsExactly(wednesday, monday, friday);
    }

    @Test
    void unitsByDateMapIsImmutable() {
        Map<LocalDate, List<Unit>> unitsByDate = new LinkedHashMap<>();
        unitsByDate.put(LocalDate.of(2026, 9, 14), List.of());

        WeekPlan weekPlan = new WeekPlan(unitsByDate, List.of());

        // Assert that calling .put() throws UnsupportedOperationException
        LocalDate newDate = LocalDate.of(2026, 9, 15);
        assertThatThrownBy(() -> weekPlan.unitsByDate().put(newDate, List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
