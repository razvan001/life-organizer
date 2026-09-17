package com.lifeorganizer.setup;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.domain.Slug;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a goals tree written with box-drawing characters (├──/└──/│) or a plain
 * "- " / two-space indent tree. Depth is measured in 4-character indent groups:
 * depth 1 = category, depth 2 = goal. Deeper levels are ignored in Phase 1.
 */
public final class GoalTreeParser {

    private static final Pattern LINE = Pattern.compile("^((?:[│├└─\\-\\s]{2}){0,}?)([│├└─][─\\s]|[-]\\s)?\\s*(.*)$");
    private static final Pattern INDENT_GROUP = Pattern.compile("[│├└─\\s]{4}|-\\s{2}|\\s{2}-\\s");

    public GoalTree parse(String text) {
        List<Category> categories = new ArrayList<>();
        List<Goal> goals = new ArrayList<>();
        Category currentCategory = null;

        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            ParsedLine parsed = parseLine(rawLine);
            if (parsed.label.isEmpty()) {
                continue;
            }
            if (parsed.depth <= 0) {
                continue; // root "Goals" line
            }
            if (parsed.depth == 1) {
                currentCategory = Category.of(parsed.label);
                categories.add(currentCategory);
            } else if (parsed.depth == 2) {
                if (currentCategory == null) {
                    throw new IllegalArgumentException("Goal '" + parsed.label + "' has no parent category");
                }
                String goalId = currentCategory.id() + "-" + Slug.of(parsed.label);
                goals.add(Goal.withDefaults(goalId, currentCategory, parsed.label));
            }
            // depth > 2 ignored in Phase 1
        }
        return new GoalTree(categories, goals);
    }

    private record ParsedLine(int depth, String label) {}

    private ParsedLine parseLine(String rawLine) {
        // Strip a leading dash-based marker ("- " or "  - ") first, since it is a
        // simpler, unambiguous alternative to the box-drawing tree.
        String line = rawLine;
        int dashDepth = 0;
        Matcher dashIndent = Pattern.compile("^(\\s*)- (.*)$").matcher(line);
        if (dashIndent.matches()) {
            int spaces = dashIndent.group(1).length();
            dashDepth = spaces / 2 + 1;
            return new ParsedLine(dashDepth, dashIndent.group(2).trim());
        }

        // Box-drawing tree: count 4-character indent/connector groups from the left.
        Matcher m = Pattern.compile("^((?:[│├└─\\s]{4})*)(.*)$").matcher(line);
        if (m.matches()) {
            int depth = m.group(1).length() / 4;
            return new ParsedLine(depth, m.group(2).trim());
        }
        return new ParsedLine(0, line.trim());
    }
}
