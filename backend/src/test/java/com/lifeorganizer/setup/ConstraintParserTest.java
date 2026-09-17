package com.lifeorganizer.setup;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import static org.assertj.core.api.Assertions.assertThat;

class ConstraintParserTest {

    private static final String USER_GOAL_TREE = """
        Goals
        ├── Career
        │   ├── Claude certification
        │   ├── Azure AI
        │   └── System design
        ├── Projects
        │   └── RAG showcase
        ├── Health
        │   ├── Gym
        │   └── Padel / football
        ├── Social
        │   └── Dating / events
        ├── Finance
        │   └── Investing
        └── Hobbies
            └── Chess
        """;

    private static final String USER_CONSTRAINTS = """
        Work:       08:30-17:00
        Gym:        4x/week
        Sleep:      23:30-07:00
        Learning:   5h/week
        Side project: 4h/week
        Social:     2 evenings/week
        """;

    private final GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);

    @Test
    void parsesWorkAsWeekdayFixedBlock() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        FixedBlock work = (FixedBlock) result.constraints().stream()
            .filter(c -> c.label().equals("Work")).findFirst().orElseThrow();
        assertThat(work.start()).isEqualTo(LocalTime.of(8, 30));
        assertThat(work.end()).isEqualTo(LocalTime.of(17, 0));
        assertThat(work.days()).isEqualTo(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    }

    @Test
    void parsesSleepAsDailyOvernightFixedBlock() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        FixedBlock sleep = (FixedBlock) result.constraints().stream()
            .filter(c -> c.label().equals("Sleep")).findFirst().orElseThrow();
        assertThat(sleep.start()).isEqualTo(LocalTime.of(23, 30));
        assertThat(sleep.end()).isEqualTo(LocalTime.of(7, 0));
        assertThat(sleep.isOvernight()).isTrue();
        assertThat(sleep.days()).isEqualTo(EnumSet.allOf(DayOfWeek.class));
    }

    @Test
    void parsesGymAsFrequencyQuotaOnTheGymGoal() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        FrequencyQuota gym = (FrequencyQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Gym")).findFirst().orElseThrow();
        assertThat(gym.timesPerWeek()).isEqualTo(4);
        assertThat(gym.sessionMinutes()).isEqualTo(60);
        assertThat(gym.goalId()).isEqualTo("health-gym");
    }

    @Test
    void parsesLearningAsDurationQuotaOnCareer() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        DurationQuota learning = (DurationQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Learning")).findFirst().orElseThrow();
        assertThat(learning.minutesPerWeek()).isEqualTo(300);
        assertThat(learning.categoryId()).isEqualTo("career");
    }

    @Test
    void parsesSideProjectAsDurationQuotaOnProjects() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        DurationQuota sideProject = (DurationQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Side project")).findFirst().orElseThrow();
        assertThat(sideProject.minutesPerWeek()).isEqualTo(240);
        assertThat(sideProject.categoryId()).isEqualTo("projects");
    }

    @Test
    void parsesSocialAsEveningQuota() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        EveningQuota social = (EveningQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Social")).findFirst().orElseThrow();
        assertThat(social.eveningsPerWeek()).isEqualTo(2);
        assertThat(social.categoryId()).isEqualTo("social");
    }

    @Test
    void producesNoWarningsForWellFormedInput() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.constraints()).hasSize(6);
    }

    @Test
    void warnsOnUnrecognizedLabel() {
        ConstraintParseResult result = new ConstraintParser().parse("Meditation: 3x/week", tree);

        assertThat(result.constraints()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("Meditation");
    }
}
