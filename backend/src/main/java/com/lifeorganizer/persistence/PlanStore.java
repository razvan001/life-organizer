package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.persistence.mapper.BlockMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists generated day plans and applies replan results by diffing. A date with no block rows
 * counts as "not generated yet" (every generated day contains at least its Wind down block).
 */
@Component
public class PlanStore {

    private static final int MAX_WARNING_LENGTH = 500;
    private static final long GENERATION_LOCK_KEY = 42_001L;

    private final BlockRepository blocks;
    private final DayWarningRepository warnings;
    private final JdbcTemplate jdbc;

    public PlanStore(BlockRepository blocks, DayWarningRepository warnings, JdbcTemplate jdbc) {
        this.blocks = blocks;
        this.warnings = warnings;
        this.jdbc = jdbc;
    }

    /**
     * Serializes first-time day generation across concurrent requests. The lock is transaction-scoped:
     * Postgres releases it at commit or rollback, so a waiting request wakes up to find the day stored.
     */
    @Transactional
    public void lockGeneration() {
        jdbc.execute("select pg_advisory_xact_lock(" + GENERATION_LOCK_KEY + ")");
    }

    @Transactional(readOnly = true)
    public Optional<DayPlan> findDay(LocalDate date) {
        List<BlockEntity> rows = blocks.findByDateOrderByStartTimeAscIdAsc(date);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Block> dayBlocks = rows.stream().map(BlockMapper::toDomain).toList();
        List<String> dayWarnings = warnings.findByDateOrderByPositionAsc(date).stream()
            .map(DayWarningEntity::getMessage)
            .toList();
        return Optional.of(new DayPlan(date, dayBlocks, dayWarnings));
    }

    @Transactional
    public void saveNewDay(DayPlan plan) {
        blocks.saveAll(withUniqueIds(plan.blocks()).stream().map(BlockMapper::toEntity).toList());
        warnings.deleteByDate(plan.date());
        int position = 0;
        for (String warning : plan.warnings()) {
            warnings.save(new DayWarningEntity(plan.date(), position++, truncate(warning)));
        }
    }

    @Transactional
    public void applyChanges(Map<LocalDate, DayPlan> before, Map<LocalDate, DayPlan> after) {
        for (Map.Entry<LocalDate, DayPlan> entry : after.entrySet()) {
            DayPlan old = before.get(entry.getKey());
            if (old != null) {
                saveDifferences(old, entry.getValue());
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<LocalDate> findBlockDate(String blockId) {
        return blocks.findById(blockId).map(BlockEntity::getDate);
    }

    private void saveDifferences(DayPlan old, DayPlan updated) {
        Map<String, Block> oldById = byId(old.blocks());
        Map<String, Block> newById = byId(updated.blocks());

        List<String> removed = oldById.keySet().stream()
            .filter(id -> !newById.containsKey(id))
            .toList();
        List<BlockEntity> changed = newById.values().stream()
            .filter(block -> !block.equals(oldById.get(block.id())))
            .map(BlockMapper::toEntity)
            .toList();

        if (!removed.isEmpty()) {
            blocks.deleteAllById(removed);
        }
        if (!changed.isEmpty()) {
            blocks.saveAll(changed);
        }
    }

    /** Last one wins if a replan ever produced two blocks with one id. */
    private static Map<String, Block> byId(List<Block> list) {
        Map<String, Block> map = new LinkedHashMap<>();
        for (Block block : list) {
            map.put(block.id(), block);
        }
        return map;
    }

    /**
     * Two units can legitimately produce the same block id (for example two duration quotas that
     * land one goal on the same day). Keep every block by suffixing later duplicates with ~2, ~3, ...
     */
    private static List<Block> withUniqueIds(List<Block> planned) {
        Set<String> seen = new HashSet<>();
        List<Block> result = new ArrayList<>();
        for (Block block : planned) {
            String id = block.id();
            int suffix = 2;
            while (!seen.add(id)) {
                id = block.id() + "~" + suffix++;
            }
            result.add(id.equals(block.id()) ? block : withId(block, id));
        }
        return result;
    }

    private static Block withId(Block block, String id) {
        return new Block(id, block.date(), block.start(), block.end(), block.title(), block.kind(), block.goalId(),
            block.status(), block.postponeCount(), block.flagged(), block.reason());
    }

    private static String truncate(String warning) {
        return warning.length() <= MAX_WARNING_LENGTH ? warning : warning.substring(0, MAX_WARNING_LENGTH);
    }
}
