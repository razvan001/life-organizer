package com.lifeorganizer.setup;

import com.lifeorganizer.domain.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one "Label: value" constraint per line into a FixedBlock, FrequencyQuota,
 * DurationQuota, or EveningQuota, resolved against an already-parsed GoalTree.
 */
public final class ConstraintParser {

    private static final Pattern LINE = Pattern.compile("^([^:]+):\\s*(.+)$");
    private static final Pattern TIME_RANGE =
        Pattern.compile("(\\d{1,2}:\\d{2})\\s*[\\u2013-]\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern FREQUENCY = Pattern.compile("(\\d+)\\s*x\\s*/\\s*week", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION =
        Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*h\\s*/\\s*week", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENINGS =
        Pattern.compile("(\\d+)\\s*evenings?\\s*/\\s*week", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> CATEGORY_ALIASES = Map.of(
        "learning", "career",
        "side project", "projects"
    );

    public ConstraintParseResult parse(String text, GoalTree tree) {
        List<Constraint> constraints = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            Matcher lineMatcher = LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) {
                warnings.add("Unrecognized line: " + rawLine.trim());
                continue;
            }
            String label = lineMatcher.group(1).trim();
            String value = lineMatcher.group(2).trim();
            parseValue(label, value, tree, constraints, warnings);
        }
        return new ConstraintParseResult(constraints, warnings);
    }

    private void parseValue(String label, String value, GoalTree tree,
                             List<Constraint> constraints, List<String> warnings) {
        Matcher time = TIME_RANGE.matcher(value);
        if (time.find()) {
            LocalTime start = LocalTime.parse(pad(time.group(1)));
            LocalTime end = LocalTime.parse(pad(time.group(2)));
            Set<DayOfWeek> days = label.equalsIgnoreCase("work")
                ? EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
                : EnumSet.allOf(DayOfWeek.class);
            constraints.add(new FixedBlock(label, start, end, days));
            return;
        }

        Matcher freq = FREQUENCY.matcher(value);
        if (freq.find()) {
            Goal goal = resolveGoal(label, tree);
            if (goal == null) {
                warnings.add("No goal found for frequency constraint: " + label);
                return;
            }
            constraints.add(new FrequencyQuota(label, Integer.parseInt(freq.group(1)), goal.defaultBlockMinutes(), goal.id()));
            return;
        }

        Matcher dur = DURATION.matcher(value);
        if (dur.find()) {
            Category category = resolveCategory(label, tree);
            if (category == null) {
                warnings.add("No category found for duration constraint: " + label);
                return;
            }
            int minutes = (int) Math.round(Double.parseDouble(dur.group(1)) * 60);
            constraints.add(new DurationQuota(label, minutes, category.id()));
            return;
        }

        Matcher eve = EVENINGS.matcher(value);
        if (eve.find()) {
            Category category = resolveCategory(label, tree);
            if (category == null) {
                warnings.add("No category found for evening constraint: " + label);
                return;
            }
            constraints.add(new EveningQuota(label, Integer.parseInt(eve.group(1)), category.id()));
            return;
        }

        warnings.add("Unrecognized value for '" + label + "': " + value);
    }

    private static String pad(String hhmm) {
        String[] parts = hhmm.split(":");
        return String.format("%02d:%s", Integer.parseInt(parts[0]), parts[1]);
    }

    private Category resolveCategory(String label, GoalTree tree) {
        for (Category c : tree.categories()) {
            if (c.name().equalsIgnoreCase(label)) {
                return c;
            }
        }
        String aliasId = CATEGORY_ALIASES.get(label.toLowerCase());
        if (aliasId != null) {
            for (Category c : tree.categories()) {
                if (c.id().equals(aliasId)) {
                    return c;
                }
            }
        }
        return null;
    }

    private Goal resolveGoal(String label, GoalTree tree) {
        for (Goal g : tree.goals()) {
            if (g.name().equalsIgnoreCase(label)) {
                return g;
            }
        }
        for (Goal g : tree.goals()) {
            if (g.name().toLowerCase().contains(label.toLowerCase())) {
                return g;
            }
        }
        return null;
    }
}
