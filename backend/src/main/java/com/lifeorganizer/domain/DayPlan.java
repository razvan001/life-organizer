package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record DayPlan(LocalDate date, List<Block> blocks, List<String> warnings) {

    public DayPlan {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(warnings, "warnings");
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
    }

    public List<Block> blocksInOrder() {
        return blocks.stream().sorted(Comparator.comparing(Block::start)).toList();
    }
}
