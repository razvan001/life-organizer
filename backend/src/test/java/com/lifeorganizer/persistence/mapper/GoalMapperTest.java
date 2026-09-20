package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.persistence.CategoryEntity;
import com.lifeorganizer.persistence.GoalEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GoalMapperTest {

    @Test
    void categoryRoundTripsAndKeepsItsPosition() {
        Category career = Category.of("Career");

        CategoryEntity entity = GoalMapper.toEntity(career, 4);

        assertThat(entity.getId()).isEqualTo("career");
        assertThat(entity.getName()).isEqualTo("Career");
        assertThat(entity.getPosition()).isEqualTo(4);
        assertThat(GoalMapper.toDomain(entity)).isEqualTo(career);
    }

    @Test
    void goalWithADeadlineRoundTrips() {
        Category career = Category.of("Career");
        Goal goal = new Goal("career-claude-certification", career, "Claude certification", 5,
            LocalDate.of(2026, 12, 1), 90, Energy.HIGH, PreferredTime.EVENING);

        GoalEntity entity = GoalMapper.toEntity(goal, GoalMapper.toEntity(career, 0), 7);

        assertThat(entity.getEnergy()).isEqualTo("HIGH");
        assertThat(entity.getPreferredTime()).isEqualTo("EVENING");
        assertThat(entity.getPosition()).isEqualTo(7);
        assertThat(GoalMapper.toDomain(entity)).isEqualTo(goal);
    }

    @Test
    void goalWithoutADeadlineRoundTrips() {
        Category health = Category.of("Health");
        Goal goal = Goal.withDefaults("health-gym", health, "Gym");

        GoalEntity entity = GoalMapper.toEntity(goal, GoalMapper.toEntity(health, 0), 0);

        assertThat(entity.getDeadline()).isNull();
        assertThat(GoalMapper.toDomain(entity)).isEqualTo(goal);
    }
}
