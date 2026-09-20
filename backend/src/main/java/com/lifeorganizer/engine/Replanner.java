package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Applies a single PlanEvent to a week of plans with minimal change: only the
 * affected block (and, for postpone, the target slot) is touched.
 */
public final class Replanner {

    private final PostponePolicy postponePolicy;

    public Replanner(PostponePolicy postponePolicy) {
        this.postponePolicy = postponePolicy;
    }

    public ReplanResult apply(Map<LocalDate, DayPlan> weekPlans, PlanEvent event) {
        return switch (event) {
            case CancelBlock c -> cancel(weekPlans, c);
            case MarkDone d -> setStatus(weekPlans, d.blockId(), BlockStatus.DONE, "Marked done");
            case PostponeBlock p -> postponePolicy.postpone(weekPlans, p);
            case SetEnergy e -> setEnergy(weekPlans, e);
            case AddAdhoc a -> addAdhoc(weekPlans, a);
        };
    }

    private ReplanResult cancel(Map<LocalDate, DayPlan> weekPlans, CancelBlock event) {
        for (Map.Entry<LocalDate, DayPlan> entry : weekPlans.entrySet()) {
            DayPlan plan = entry.getValue();
            Optional<Block> match = plan.blocks().stream().filter(b -> b.id().equals(event.blockId())).findFirst();
            if (match.isPresent()) {
                Block original = match.get();
                Block cancelled = new Block(original.id(), original.date(), original.start(), original.end(), original.title(),
                    original.kind(), original.goalId(), BlockStatus.CANCELLED, original.postponeCount(), original.flagged(), original.reason());
                Block freedSlot = new Block(original.id() + "-freed", original.date(), original.start(), original.end(), "Free",
                    BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
                List<Block> blocks = new ArrayList<>(plan.blocks());
                blocks.replaceAll(b -> b.id().equals(event.blockId()) ? cancelled : b);
                blocks.add(freedSlot);
                Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);
                updated.put(entry.getKey(), new DayPlan(plan.date(), blocks, plan.warnings()));
                return new ReplanResult(updated, List.of("Cancelled " + original.title() + " - slot is now free"));
            }
        }
        return new ReplanResult(weekPlans, List.of("Block " + event.blockId() + " not found"));
    }

    private ReplanResult setStatus(Map<LocalDate, DayPlan> weekPlans, String blockId, BlockStatus status, String verb) {
        for (Map.Entry<LocalDate, DayPlan> entry : weekPlans.entrySet()) {
            DayPlan plan = entry.getValue();
            Optional<Block> match = plan.blocks().stream().filter(b -> b.id().equals(blockId)).findFirst();
            if (match.isPresent()) {
                Block original = match.get();
                Block updatedBlock = new Block(original.id(), original.date(), original.start(), original.end(), original.title(),
                    original.kind(), original.goalId(), status, original.postponeCount(), original.flagged(), original.reason());
                List<Block> blocks = new ArrayList<>(plan.blocks());
                blocks.replaceAll(b -> b.id().equals(blockId) ? updatedBlock : b);
                Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);
                updated.put(entry.getKey(), new DayPlan(plan.date(), blocks, plan.warnings()));
                return new ReplanResult(updated, List.of(verb + " " + original.title()));
            }
        }
        return new ReplanResult(weekPlans, List.of("Block " + blockId + " not found"));
    }

    private ReplanResult setEnergy(Map<LocalDate, DayPlan> weekPlans, SetEnergy event) {
        // Phase 1: recorded as a change note only; energy-based re-sorting of
        // remaining units is added once PlanService (Phase 1b) can re-run WeekAllocator.
        return new ReplanResult(weekPlans, List.of("Energy set to " + event.level() + " for rest of day"));
    }

    private ReplanResult addAdhoc(Map<LocalDate, DayPlan> weekPlans, AddAdhoc event) {
        DayPlan plan = weekPlans.get(event.date());
        if (plan == null) {
            return new ReplanResult(weekPlans, List.of("No plan exists for " + event.date()));
        }
        Block adhoc = new Block(uniqueAdhocId(plan, event), event.date(), event.start(), event.end(),
            event.title(), BlockKind.ADHOC, null, BlockStatus.PLANNED, 0, false, null);

        // The ad-hoc block takes over any free time it overlaps: each overlapped FREE block is
        // replaced by whatever free time is left before and/or after the ad-hoc range.
        List<Block> blocks = new ArrayList<>();
        for (Block existing : plan.blocks()) {
            if (existing.kind() == BlockKind.FREE && existing.status() == BlockStatus.PLANNED && existing.overlaps(adhoc)) {
                if (existing.start().isBefore(adhoc.start())) {
                    blocks.add(freeFragment(existing, existing.id() + "-before", existing.start(), adhoc.start()));
                }
                if (existing.end().isAfter(adhoc.end())) {
                    blocks.add(freeFragment(existing, existing.id() + "-after", adhoc.end(), existing.end()));
                }
            } else {
                blocks.add(existing);
            }
        }
        blocks.add(adhoc);

        Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);
        updated.put(event.date(), new DayPlan(plan.date(), blocks, plan.warnings()));
        return new ReplanResult(updated, List.of("Added " + event.title() + " at " + event.start()));
    }

    /** Two ad-hocs may share date and start; keep ids unique within the day (mirrors PlanStore's "~n" suffix). */
    private static String uniqueAdhocId(DayPlan plan, AddAdhoc event) {
        Set<String> taken = new HashSet<>();
        plan.blocks().forEach(b -> taken.add(b.id()));
        String base = "adhoc-" + event.date() + "-" + event.start();
        String id = base;
        int suffix = 2;
        while (taken.contains(id)) {
            id = base + "~" + suffix++;
        }
        return id;
    }

    private static Block freeFragment(Block source, String id, LocalTime start, LocalTime end) {
        return new Block(id, source.date(), start, end, "Free", BlockKind.FREE, null,
            BlockStatus.PLANNED, 0, false, null);
    }
}
