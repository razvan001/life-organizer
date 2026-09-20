package com.lifeorganizer.api;

import com.lifeorganizer.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeFormatsTest {

    @Test
    void formatsTimesAsHourMinute() {
        assertThat(TimeFormats.format(LocalTime.of(7, 0))).isEqualTo("07:00");
        assertThat(TimeFormats.format(LocalTime.of(23, 30))).isEqualTo("23:30");
    }

    @Test
    void formatsDatesAsIso() {
        assertThat(TimeFormats.format(LocalDate.of(2026, 9, 14))).isEqualTo("2026-09-14");
    }

    @Test
    void parsesStrictHourMinute() {
        assertThat(TimeFormats.parseTime("08:30", "start")).isEqualTo(LocalTime.of(8, 30));
    }

    @Test
    void rejectsASingleDigitHour() {
        assertThatThrownBy(() -> TimeFormats.parseTime("8:30", "start"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("start must be HH:mm, was '8:30'");
    }

    @Test
    void rejectsAnOutOfRangeTimeIncludingTwentyFourHundred() {
        assertThatThrownBy(() -> TimeFormats.parseTime("25:00", "end"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("end must be HH:mm");
        assertThatThrownBy(() -> TimeFormats.parseTime("24:00", "end"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("end must be HH:mm, was '24:00'");
    }

    @Test
    void rejectsBlankAndNullTimes() {
        assertThatThrownBy(() -> TimeFormats.parseTime("  ", "start"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("start is required (HH:mm)");
        assertThatThrownBy(() -> TimeFormats.parseTime(null, "start"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("start is required (HH:mm)");
    }

    @Test
    void parsesIsoDatesAndRejectsOtherFormats() {
        assertThat(TimeFormats.parseDate("2026-09-14", "date")).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThatThrownBy(() -> TimeFormats.parseDate("14/09/2026", "date"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("date must be yyyy-MM-dd, was '14/09/2026'");
    }
}
