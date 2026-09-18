package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Decides where a postponed block lands: a later free slot the same day, else
 * the first free slot on a later day already present in the given week. The
 * original block is marked POSTPONED in place; a new PLANNED block is created
 * at the target slot carrying postponeCount + 1. At postponeCount 3 the new
 * block is flagged.
 */
public final class PostponePolicy {

    public ReplanResult postpone(Map<LocalDate, DayPlan> weekPlans, PostponeBlock event) {
        LocalDate originDate = findBlockDate(weekPlans, event.blockId());
        if (originDate == null) {
            return new ReplanResult(weekPlans, List.of("Block " + event.blockId() + " not found"));
        }
        DayPlan originPlan = weekPlans.get(originDate);
        Block original = originPlan.blocks().stream().filter(b -> b.id().equals(event.blockId())).findFirst().orElseThrow();

        if (original.kind() == BlockKind.FIXED || original.kind() == BlockKind.ROUTINE) {
            return new ReplanResult(weekPlans, List.of("Cannot postpone a " + original.kind() + " block (" + original.title() + ")"));
        }
        if (original.status() == BlockStatus.DONE) {
            return new ReplanResult(weekPlans, List.of("Cannot postpone a completed block (" + original.title() + ")"));
        }

        List<LocalDate> candidateDates = new ArrayList<>();
        candidateDates.add(originDate);
        weekPlans.keySet().stream().filter(d -> d.isAfter(originDate)).sorted().forEach(candidateDates::add);

        for (LocalDate candidateDate : candidateDates) {
            DayPlan candidatePlan = weekPlans.get(candidateDate);
            Block freeSlot = findFreeSlotAfter(candidatePlan, original, candidateDate.equals(originDate) ? original.end() : LocalTime.MIN);
            if (freeSlot != null) {
                return moveInto(weekPlans, originDate, candidateDate, original, freeSlot, event.reason());
            }
        }

        Map<LocalDate, DayPlan> unchanged = new LinkedHashMap<>(weekPlans);
        return new ReplanResult(unchanged, List.of("No room this week to reschedule " + original.title()));
    }

    private Block findFreeSlotAfter(DayPlan plan, Block original, LocalTime notBefore) {
        return plan.blocksInOrder().stream()
            .filter(b -> b.kind() == BlockKind.FREE)
            .filter(b -> !b.start().isBefore(notBefore))
            .filter(b -> b.durationMinutes() >= original.durationMinutes())
            .findFirst()
            .orElse(null);
    }

    private ReplanResult moveInto(Map<LocalDate, DayPlan> weekPlans, LocalDate originDate, LocalDate targetDate,
                                   Block original, Block freeSlot, String reason) {
        Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);

        Block postponedOriginal = new Block(original.id(), original.date(), original.start(), original.end(), original.title(),
            original.kind(), original.goalId(), BlockStatus.POSTPONED, original.postponeCount(), original.flagged(), reason);
        updated.put(originDate, replaceBlock(updated.get(originDate), original.id(), postponedOriginal));

        int newPostponeCount = original.postponeCount() + 1;
        LocalTime newStart = freeSlot.start();
        LocalTime newEnd = newStart.plusMinutes(original.durationMinutes());
        Block moved = new Block(original.id() + "-p" + newPostponeCount, targetDate, newStart, newEnd, original.title(),
            original.kind(), original.goalId(), BlockStatus.PLANNED, newPostponeCount, newPostponeCount >= 3, null);

        DayPlan targetPlan = updated.get(targetDate);
        List<Block> targetBlocks = new ArrayList<>(targetPlan.blocks());
        if (targetDate.equals(originDate)) {
            targetBlocks = updated.get(targetDate).blocks();
            targetBlocks = new ArrayList<>(targetBlocks);
        }
        targetBlocks.removeIf(b -> b.id().equals(freeSlot.id()));
        splitRemainingFree(freeSlot, moved).ifPresent(targetBlocks::add);
        targetBlocks.add(moved);
        updated.put(targetDate, new DayPlan(targetDate, targetBlocks, targetPlan.warnings()));

        String change = "Moved " + original.title() + " " + original.start() + " -> " + targetDate + " " + newStart;
        return new ReplanResult(updated, List.of(change));
    }

    private Optional<Block> splitRemainingFree(Block freeSlot, Block moved) {
        if (moved.end().isBefore(freeSlot.end())) {
            return Optional.of(new Block(freeSlot.id() + "-rest", freeSlot.date(), moved.end(), freeSlot.end(), "Free",
                BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null));
        }
        return Optional.empty();
    }

    private DayPlan replaceBlock(DayPlan plan, String blockId, Block replacement) {
        List<Block> blocks = new ArrayList<>(plan.blocks());
        blocks.replaceAll(b -> b.id().equals(blockId) ? replacement : b);
        return new DayPlan(plan.date(), blocks, plan.warnings());
    }

    private LocalDate findBlockDate(Map<LocalDate, DayPlan> weekPlans, String blockId) {
        for (Map.Entry<LocalDate, DayPlan> entry : weekPlans.entrySet()) {
            if (entry.getValue().blocks().stream().anyMatch(b -> b.id().equals(blockId))) {
                return entry.getKey();
            }
        }
        return null;
    }
}
