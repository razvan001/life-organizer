package com.lifeorganizer.ai.agent;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One readable line per block that is new, or whose status or start time changed (same idea as the UI's ProposalCard). */
public final class PlanDiff {

    private PlanDiff() {
    }

    public static List<String> lines(List<Block> before, List<Block> after) {
        Map<String, Block> old = before.stream().collect(Collectors.toMap(Block::id, Function.identity(), (a, b) -> b));
        List<String> lines = new ArrayList<>();
        for (Block next : after) {
            Block previous = old.get(next.id());
            if (previous == null) {
                lines.add(next.title() + " " + TimeFormats.format(next.start()) + "-" + TimeFormats.format(next.end())
                    + " -> new");
            } else if (previous.status() != next.status() || !previous.start().equals(next.start())) {
                lines.add(previous.title() + " " + TimeFormats.format(previous.start()) + " -> " + next.status() + " "
                    + TimeFormats.format(next.start()));
            }
        }
        return lines;
    }
}
