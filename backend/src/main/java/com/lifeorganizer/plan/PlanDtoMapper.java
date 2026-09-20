package com.lifeorganizer.plan;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.plan.dto.ApplyEventResponse;
import com.lifeorganizer.plan.dto.BlockDto;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

/** Converts domain plans into the string-only JSON shapes; blocks are always sorted by start time. */
public final class PlanDtoMapper {

    private PlanDtoMapper() {
    }

    public static BlockDto toDto(Block block) {
        return new BlockDto(block.id(), TimeFormats.format(block.date()), TimeFormats.format(block.start()),
            TimeFormats.format(block.end()), block.title(), block.kind().name(), block.goalId(),
            block.status().name(), block.postponeCount(), block.flagged(), block.reason());
    }

    public static TodayPlanResponse toResponse(DayPlan plan) {
        return new TodayPlanResponse(TimeFormats.format(plan.date()), blocksOf(plan), plan.warnings());
    }

    public static ApplyEventResponse toResponse(AppliedEvent applied) {
        DayPlan today = applied.today();
        return new ApplyEventResponse(TimeFormats.format(today.date()), blocksOf(today), today.warnings(),
            applied.changes());
    }

    private static List<BlockDto> blocksOf(DayPlan plan) {
        return plan.blocksInOrder().stream().map(PlanDtoMapper::toDto).toList();
    }
}
