package com.lifeorganizer.setup.api;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupDtoMapperTest {

    private static final Category CAREER = Category.of("Career");

    private static Constraint roundTrip(Constraint constraint) {
        return SetupDtoMapper.toDomain(SetupDtoMapper.toDto(constraint));
    }

    @Test
    void everyConstraintTypeRoundTripsThroughItsDto() {
        List<Constraint> all = List.of(
            new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), EnumSet.allOf(DayOfWeek.class)),
            new FrequencyQuota("Gym", 4, 60, "health-gym"),
            new DurationQuota("Learning", 300, "career"),
            new EveningQuota("Social", 2, "social"));

        for (Constraint constraint : all) {
            assertThat(roundTrip(constraint)).isEqualTo(constraint);
        }
    }

    @Test
    void fixedBlockDtoUsesHourMinuteTimesAndWeekOrderedDayNames() {
        ConstraintDto dto = SetupDtoMapper.toDto(new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)));

        assertThat(dto).isEqualTo(new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00",
            List.of("MONDAY", "WEDNESDAY"), null, null, null, null, null, null));
    }

    @Test
    void quotaDtosCarryOnlyTheirOwnFields() {
        assertThat(SetupDtoMapper.toDto(new FrequencyQuota("Gym", 4, 60, "health-gym")))
            .isEqualTo(new ConstraintDto("FREQUENCY_QUOTA", "Gym", null, null, null, 4, 60, "health-gym", null, null, null));
        assertThat(SetupDtoMapper.toDto(new DurationQuota("Learning", 300, "career")))
            .isEqualTo(new ConstraintDto("DURATION_QUOTA", "Learning", null, null, null, null, null, null, 300, "career", null));
        assertThat(SetupDtoMapper.toDto(new EveningQuota("Social", 2, "social")))
            .isEqualTo(new ConstraintDto("EVENING_QUOTA", "Social", null, null, null, null, null, null, null, "social", 2));
    }

    @Test
    void goalRoundTripsThroughItsDtoIncludingTheDeadline() {
        Goal goal = new Goal("career-cert", CAREER, "Claude certification", 5, LocalDate.of(2026, 12, 1), 90,
            Energy.HIGH, PreferredTime.EVENING);

        GoalDto dto = SetupDtoMapper.toDto(goal);

        assertThat(dto).isEqualTo(new GoalDto("career-cert", "career", "Claude certification", 5, "2026-12-01", 90,
            "HIGH", "EVENING"));
        assertThat(SetupDtoMapper.toDomain(dto, Map.of("career", CAREER))).isEqualTo(goal);
    }

    @Test
    void unknownConstraintTypeIsRejected() {
        ConstraintDto dto = new ConstraintDto("WAT", "X", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Unknown constraint type 'WAT'");
    }

    @Test
    void aMissingQuotaFieldNamesTheField() {
        ConstraintDto dto = new ConstraintDto("FREQUENCY_QUOTA", "Gym", null, null, null, null, 60, "health-gym", null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("timesPerWeek is required");
    }

    @Test
    void aBadTimeAndABadDayNameAreRejected() {
        ConstraintDto badTime = new ConstraintDto("FIXED_BLOCK", "Work", "8:30", "17:00", List.of("MONDAY"),
            null, null, null, null, null, null);
        ConstraintDto badDay = new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00", List.of("FUNDAY"),
            null, null, null, null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(badTime))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("start must be HH:mm");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(badDay))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("days must be one of");
    }

    @Test
    void aGoalWithAnUnknownCategoryIsRejected() {
        GoalDto dto = new GoalDto("x", "nope", "X", 3, null, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Goal 'x' refers to unknown category 'nope'");
    }

    @Test
    void aDomainRuleViolationBecomesAReadableError() {
        GoalDto dto = new GoalDto("x", "career", "X", 9, null, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("goal 'x': priority must be between 1 and 5, was 9");
    }

    @Test
    void aBadEnergyNamesTheAllowedValues() {
        GoalDto dto = new GoalDto("x", "career", "X", 3, null, 60, "SUPER", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("energy must be one of [LOW, MEDIUM, HIGH], was 'SUPER'");
    }
}
