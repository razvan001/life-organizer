package com.lifeorganizer.support;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** The user's own example from the original design spec, parsed with the real Phase 1a parsers. */
public final class ExampleSetup {

    public static final String GOALS_TEXT = """
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

    public static final String CONSTRAINTS_TEXT = """
        Work:       08:30-17:00
        Gym:        4x/week
        Sleep:      23:30-07:00
        Learning:   5h/week
        Side project: 4h/week
        Social:     2 evenings/week
        """;

    /** A fixed Monday so week-based expectations are stable. */
    public static final LocalDate MONDAY =
        LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    private ExampleSetup() {
    }

    public static GoalTree tree() {
        return new GoalTreeParser().parse(GOALS_TEXT);
    }

    public static List<Constraint> constraints() {
        return new ConstraintParser().parse(CONSTRAINTS_TEXT, tree()).constraints();
    }
}
