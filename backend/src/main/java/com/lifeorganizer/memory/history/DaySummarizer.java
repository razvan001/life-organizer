package com.lifeorganizer.memory.history;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.DayPlan;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Turns one past day into a line of plain text worth remembering: what happened to goal and ad-hoc blocks. */
public final class DaySummarizer {

    private static final Set<BlockKind> REMEMBERED = Set.of(BlockKind.GOAL, BlockKind.ADHOC);

    private DaySummarizer() {
    }

    public static String title(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date;
    }

    public static Optional<String> summarize(DayPlan day) {
        List<String> parts = day.blocksInOrder().stream()
            .filter(block -> REMEMBERED.contains(block.kind()))
            .map(DaySummarizer::describe)
            .toList();
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(title(day.date()) + ": " + String.join("; ", parts));
    }

    private static String describe(Block block) {
        String outcome = switch (block.status()) {
            case DONE -> "done";
            case POSTPONED -> "postponed";
            case CANCELLED -> "cancelled";
            case PLANNED -> "not marked done";
        };
        String text = block.title() + " " + outcome + " " + TimeFormats.format(block.start());
        if (block.reason() != null && !block.reason().isBlank()) {
            text += " (reason: " + block.reason() + ")";
        }
        return text;
    }
}
