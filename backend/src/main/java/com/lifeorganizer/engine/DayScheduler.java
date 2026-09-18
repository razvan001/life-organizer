package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Places one day's Units into the free time left after fixed blocks (Work, ...)
 * and routine blocks (Dinner after work, Wind down before sleep). The active
 * window for the day is bounded by the "Sleep" FixedBlock: it runs from Sleep's
 * end time (wake time) to Sleep's start time (bedtime) and is never rendered as
 * a block itself. Leftover gaps of at least minBlockMinutes become FREE blocks.
 */
public final class DayScheduler {

    private final EngineSettings settings;

    public DayScheduler(EngineSettings settings) {
        this.settings = settings;
    }

    public DayPlan schedule(LocalDate date, List<Unit> units, List<Constraint> constraints, List<Block> alreadyPlacedToday) {
        List<String> warnings = new ArrayList<>();
        FixedBlock sleep = findSleepBlock(constraints);
        LocalTime dayStart = sleep != null ? sleep.end() : LocalTime.of(7, 0);
        LocalTime dayEnd = sleep != null ? sleep.start() : LocalTime.of(23, 0);

        List<Block> placed = new ArrayList<>(alreadyPlacedToday);
        DayOfWeek dow = date.getDayOfWeek();

        FixedBlock workBlock = null;
        for (Constraint c : constraints) {
            if (c instanceof FixedBlock fb && fb != sleep && fb.days().contains(dow)) {
                if (fb.isOvernight()) {
                    warnings.add("Skipping overnight block '" + fb.label() + "' on " + date + " (only Sleep supports overnight ranges)");
                    continue;
                }
                String id = "fixed-" + Slug.of(fb.label()) + "-" + date;
                placed.add(new Block(id, date, fb.start(), fb.end(), fb.label(), BlockKind.FIXED, null,
                    BlockStatus.PLANNED, 0, false, null));
                if (fb.label().equalsIgnoreCase("work")) {
                    workBlock = fb;
                }
            }
        }

        if (workBlock != null) {
            LocalTime dinnerStart = workBlock.end();
            LocalTime dinnerEnd = dinnerStart.plusMinutes(settings.dinnerMinutesAfterWork());
            if (!dinnerEnd.isAfter(dayEnd)) {
                placed.add(new Block("routine-dinner-" + date, date, dinnerStart, dinnerEnd, "Dinner / break",
                    BlockKind.ROUTINE, null, BlockStatus.PLANNED, 0, false, null));
            }
        }
        LocalTime windDownStart = dayEnd.minusMinutes(settings.windDownMinutesBeforeSleep());
        placed.add(new Block("routine-winddown-" + date, date, windDownStart, dayEnd, "Wind down",
            BlockKind.ROUTINE, null, BlockStatus.PLANNED, 0, false, null));

        for (Unit unit : units) {
            List<Interval> freeGaps = freeGaps(dayStart, dayEnd, placed);
            LocalTime start = pickStart(freeGaps, unit);
            if (start == null) {
                warnings.add("No room today for " + unit.goal().name());
                continue;
            }
            LocalTime end = start.plusMinutes(unit.minutes());
            placed.add(new Block(unit.id(), date, start, end, unit.goal().name(), BlockKind.GOAL, unit.goal().id(),
                BlockStatus.PLANNED, 0, false, null));
        }

        for (Interval gap : freeGaps(dayStart, dayEnd, placed)) {
            int minutes = (int) Duration.between(gap.start(), gap.end()).toMinutes();
            if (minutes >= settings.minBlockMinutes()) {
                placed.add(new Block("free-" + date + "-" + gap.start(), date, gap.start(), gap.end(), "Free",
                    BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null));
            }
        }

        return new DayPlan(date, placed, warnings);
    }

    private FixedBlock findSleepBlock(List<Constraint> constraints) {
        for (Constraint c : constraints) {
            if (c instanceof FixedBlock fb && fb.label().equalsIgnoreCase("sleep")) {
                return fb;
            }
        }
        return null;
    }

    private record Interval(LocalTime start, LocalTime end) {
        public Interval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end must be after start");
            }
        }
    }

    private List<Interval> freeGaps(LocalTime dayStart, LocalTime dayEnd, List<Block> placed) {
        List<Block> sorted = placed.stream().sorted(Comparator.comparing(Block::start)).toList();
        List<Interval> gaps = new ArrayList<>();
        LocalTime cursor = dayStart;
        for (Block b : sorted) {
            if (b.start().isAfter(cursor)) {
                gaps.add(new Interval(cursor, b.start()));
            }
            if (b.end().isAfter(cursor)) {
                cursor = b.end();
            }
        }
        if (dayEnd.isAfter(cursor)) {
            gaps.add(new Interval(cursor, dayEnd));
        }
        return gaps;
    }

    /**
     * Chooses the start time for a unit. A non-null anchorStart wins if some gap
     * can hold the full duration starting no earlier than the anchor and ending
     * no later than the gap's end; otherwise (or if the anchor doesn't fit
     * anywhere) the best-fitting gap is chosen by preferredTime and filled from
     * its start.
     */
    private LocalTime pickStart(List<Interval> gaps, Unit unit) {
        List<Interval> fitting = gaps.stream()
            .filter(g -> Duration.between(g.start(), g.end()).toMinutes() >= unit.minutes())
            .toList();
        if (fitting.isEmpty()) {
            return null;
        }
        if (unit.anchorStart() != null) {
            for (Interval g : fitting) {
                LocalTime latestStart = g.end().minusMinutes(unit.minutes());
                if (!unit.anchorStart().isBefore(g.start()) && !unit.anchorStart().isAfter(latestStart)) {
                    return unit.anchorStart();
                }
            }
            // anchor time isn't free in any gap; fall through to best-fit below
        }
        Comparator<Interval> byPreference = Comparator.comparing((Interval g) -> preferenceScore(g, unit.preferredTime()));
        return fitting.stream().min(byPreference).map(Interval::start).orElse(null);
    }

    private int preferenceScore(Interval gap, PreferredTime preferredTime) {
        boolean morning = gap.start().isBefore(LocalTime.NOON);
        return switch (preferredTime) {
            case MORNING -> morning ? 0 : 1;
            case EVENING -> morning ? 1 : 0;
            case ANY -> 0;
        };
    }
}
