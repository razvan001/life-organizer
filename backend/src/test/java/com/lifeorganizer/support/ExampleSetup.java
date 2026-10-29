package com.lifeorganizer.support;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.domain.Slug;
import com.lifeorganizer.setup.api.SetupDtoMapper;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's own example from the original design spec, with hours per week on each goal. The hours and times of
 * day are chosen so every weekday looks like the spec's example Monday: Gym in the morning before Work, then Claude
 * certification, RAG showcase and Dating / events in the evening (one hour each, every day). The other goals have
 * no hours and get no time.
 */
public final class ExampleSetup {

    /** A fixed Monday so week-based expectations are stable. */
    public static final LocalDate MONDAY =
        LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    private static final int DAILY = 7 * 60;

    private record ExampleGoal(String name, int minutesPerWeek, PreferredTime preferredTime) {
    }

    /** Category name, then its goals, in setup order. */
    private static final Map<String, List<ExampleGoal>> GOALS = new LinkedHashMap<>();

    static {
        GOALS.put("Career", List.of(new ExampleGoal("Claude certification", DAILY, PreferredTime.EVENING),
            new ExampleGoal("Azure AI", 0, PreferredTime.ANY), new ExampleGoal("System design", 0, PreferredTime.ANY)));
        GOALS.put("Projects", List.of(new ExampleGoal("RAG showcase", DAILY, PreferredTime.EVENING)));
        GOALS.put("Health", List.of(new ExampleGoal("Gym", DAILY, PreferredTime.MORNING),
            new ExampleGoal("Padel / football", 0, PreferredTime.ANY)));
        GOALS.put("Social", List.of(new ExampleGoal("Dating / events", DAILY, PreferredTime.EVENING)));
        GOALS.put("Finance", List.of(new ExampleGoal("Investing", 0, PreferredTime.ANY)));
        GOALS.put("Hobbies", List.of(new ExampleGoal("Chess", 0, PreferredTime.ANY)));
    }

    private ExampleSetup() {
    }

    public static GoalTree tree() {
        List<Category> categories = new ArrayList<>();
        List<Goal> goals = new ArrayList<>();
        GOALS.forEach((categoryName, goalsOfCategory) -> {
            Category category = Category.of(categoryName);
            categories.add(category);
            goalsOfCategory.forEach(goal -> goals.add(new Goal(category.id() + "-" + Slug.of(goal.name()),
                category, goal.name(), goal.minutesPerWeek(), 60, Energy.MEDIUM, goal.preferredTime())));
        });
        return new GoalTree(categories, goals);
    }

    public static List<Constraint> constraints() {
        return List.of(
            new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), EnumSet.allOf(DayOfWeek.class)));
    }

    /** The example as the setup API takes it, as the Setup page would send it. */
    public static SetupPayload payload() {
        GoalTree tree = tree();
        return new SetupPayload(
            tree.categories().stream().map(SetupDtoMapper::toDto).toList(),
            tree.goals().stream().map(SetupDtoMapper::toDto).toList(),
            constraints().stream().map(SetupDtoMapper::toDto).toList());
    }

    /** {@link #payload()} as the JSON body of {@code PUT /api/setup}. */
    public static String payloadJson() {
        return JsonMapper.builder().build().writeValueAsString(payload());
    }
}
