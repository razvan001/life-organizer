package com.lifeorganizer.setup.api;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.CategoryDto;
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
    void fixedBlocksRoundTripThroughTheirDto() {
        List<Constraint> all = List.of(
            new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), EnumSet.allOf(DayOfWeek.class)));

        for (Constraint constraint : all) {
            assertThat(roundTrip(constraint)).isEqualTo(constraint);
        }
    }

    @Test
    void fixedBlockDtoUsesHourMinuteTimesAndWeekOrderedDayNames() {
        ConstraintDto dto = SetupDtoMapper.toDto(new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)));

        assertThat(dto).isEqualTo(new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00",
            List.of("MONDAY", "WEDNESDAY")));
    }

    @Test
    void goalRoundTripsThroughItsDtoIncludingItsHours() {
        Goal goal = new Goal("career-cert", CAREER, "Claude certification", 150, 90,
            Energy.HIGH, PreferredTime.EVENING);

        GoalDto dto = SetupDtoMapper.toDto(goal);

        assertThat(dto).isEqualTo(new GoalDto("career-cert", "career", "Claude certification", 150, 90,
            "HIGH", "EVENING"));
        assertThat(SetupDtoMapper.toDomain(dto, Map.of("career", CAREER))).isEqualTo(goal);
    }

    @Test
    void overlongTextFieldsAreRejectedAsBadRequests() {
        String tooLong = "x".repeat(256);
        String justRight = "x".repeat(255);
        Map<String, Category> categories = Map.of("career", CAREER);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(new CategoryDto(tooLong, "Career")))
            .isInstanceOf(InvalidRequestException.class).hasMessage("category id must be at most 255 characters");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(new CategoryDto("career", tooLong)))
            .isInstanceOf(InvalidRequestException.class).hasMessage("category name must be at most 255 characters");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(new GoalDto(tooLong, "career", "X", 120, 60, "MEDIUM", "ANY"), categories))
            .isInstanceOf(InvalidRequestException.class).hasMessage("goal id must be at most 255 characters");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(new GoalDto("x", "career", tooLong, 120, 60, "MEDIUM", "ANY"), categories))
            .isInstanceOf(InvalidRequestException.class).hasMessage("goal name must be at most 255 characters");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(new GoalDto("x", tooLong, "X", 120, 60, "MEDIUM", "ANY"), categories))
            .isInstanceOf(InvalidRequestException.class).hasMessage("goal categoryId must be at most 255 characters");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(
            new ConstraintDto("FIXED_BLOCK", tooLong, "08:30", "17:00", List.of("MONDAY"))))
            .isInstanceOf(InvalidRequestException.class).hasMessage("constraint label must be at most 255 characters");

        // exactly 255 is still fine
        assertThat(SetupDtoMapper.toDomain(new CategoryDto("career", justRight)).name()).isEqualTo(justRight);
    }

    @Test
    void unknownConstraintTypeIsRejected() {
        ConstraintDto dto = new ConstraintDto("WAT", "X", null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Unknown constraint type 'WAT'");
    }

    @Test
    void aBadTimeAndABadDayNameAreRejected() {
        ConstraintDto badTime = new ConstraintDto("FIXED_BLOCK", "Work", "8:30", "17:00", List.of("MONDAY"));
        ConstraintDto badDay = new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00", List.of("FUNDAY"));

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(badTime))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("start must be HH:mm");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(badDay))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("days must be one of");
    }

    @Test
    void aGoalWithAnUnknownCategoryIsRejected() {
        GoalDto dto = new GoalDto("x", "nope", "X", 120, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Goal 'x' refers to unknown category 'nope'");
    }

    @Test
    void aDomainRuleViolationBecomesAReadableError() {
        GoalDto dto = new GoalDto("x", "career", "X", 120, 0, "MEDIUM", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("goal 'x': defaultBlockMinutes must be positive");
    }

    @Test
    void aBadEnergyNamesTheAllowedValues() {
        GoalDto dto = new GoalDto("x", "career", "X", 120, 60, "SUPER", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("energy must be one of [LOW, MEDIUM, HIGH], was 'SUPER'");
    }
}
