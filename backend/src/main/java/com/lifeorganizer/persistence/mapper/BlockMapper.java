package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.persistence.BlockEntity;

public final class BlockMapper {

    private BlockMapper() {
    }

    public static BlockEntity toEntity(Block block) {
        return new BlockEntity(block.id(), block.date(), block.start(), block.end(), block.title(),
            block.kind().name(), block.goalId(), block.status().name(), block.postponeCount(),
            block.flagged(), block.reason());
    }

    public static Block toDomain(BlockEntity entity) {
        return new Block(entity.getId(), entity.getDate(), entity.getStartTime(), entity.getEndTime(),
            entity.getTitle(), BlockKind.valueOf(entity.getKind()), entity.getGoalId(),
            BlockStatus.valueOf(entity.getStatus()), entity.getPostponeCount(), entity.isFlagged(),
            entity.getReason());
    }
}
