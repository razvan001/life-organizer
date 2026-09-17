package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record DayPlan(LocalDate date, List<Block> blocks, List<String> warnings) {

    public List<Block> blocksInOrder() {
        return blocks.stream().sorted(Comparator.comparing(Block::start)).toList();
    }
}
